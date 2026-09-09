package net.vulkanmod.api;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.Initializer;
import net.vulkanmod.compat.external.ExternalTerrainRenderBridge;
import net.vulkanmod.gl.GlTexture;
import net.vulkanmod.plugin.HookRegistry;
import net.vulkanmod.plugin.hooks.annotations.ListenedEvent;
import net.vulkanmod.plugin.hooks.events.render.frame.FrameBeginBeforeEvent;
import net.vulkanmod.render.DepthSnapshot;
import net.vulkanmod.render.HiZPyramid;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.chunk.buffer.AreaBuffer;
import net.vulkanmod.render.chunk.buffer.DrawBuffers;
import net.vulkanmod.render.chunk.buffer.UploadManager;
import net.vulkanmod.render.culling.DepthOcclusion;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.memory.IndirectBuffer;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.StorageBuffer;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.PipelineManager;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

public final class CalderaBridge {

    public static final int PASS_FLAT_OPAQUE = 0;
    public static final int PASS_TEX_OPAQUE = 1;
    public static final int PASS_FLAT_WATER = 2;
    public static final int PASS_TEX_WATER = 3;
    public static final int PASS_FLAT_OPAQUE_SOLID = 4;
    public static final int PASS_TEX_OPAQUE_SOLID = 5;

    private static final int NUM_PASSES = 6;

    private static final int INPUT_VERTEX_SIZE_BYTES = 16;
    private static final int INTERNAL_VERTEX_SIZE_BYTES = CustomVertexFormat.EXTERNAL_LOD.getVertexSize();

    private static final int INPUT_TEXTURED_VERTEX_SIZE_BYTES = 40;
    private static final int INTERNAL_TEXTURED_VERTEX_SIZE_BYTES = CustomVertexFormat.EXTERNAL_LOD_TEXTURED.getVertexSize();

    private static final int Y_BIAS = 2048;
    private static final int POSITION_MAX = 0xFFFF;

    private static final int ARENA_INDEX_SIZE_BYTES = 4;
    private static final int OPAQUE_ARENA_VERTEX_BYTES = 4_194_304;
    private static final int OPAQUE_ARENA_INDEX_BYTES = 2_097_152;
    private static final int WATER_ARENA_VERTEX_BYTES = 1_048_576;
    private static final int WATER_ARENA_INDEX_BYTES = 524_288;

    private static final int ORIGIN_SLOTS = 1024;
    private static final int ORIGIN_STRIDE_BYTES = 16;
    private static final int MAX_BATCH_CELLS = ORIGIN_SLOTS - 1;
    private static final int INDIRECT_COMMAND_BYTES = 20;

    private static final int MAX_CHUNKS_PER_PASS = 8;
    private static final int INDIRECT_CHUNK_BUFFER_BYTES = MAX_BATCH_CELLS * INDIRECT_COMMAND_BYTES;
    private static final int INDIRECT_PASS_BUFFER_BYTES = INDIRECT_CHUNK_BUFFER_BYTES * MAX_CHUNKS_PER_PASS;
    private static final int COUNT_PASS_BUFFER_BYTES = MAX_CHUNKS_PER_PASS * 4;

    private static final Matrix4f COMBINED_MATRIX_SCRATCH = new Matrix4f();
    private static final int LIGHTMAP_TEXTURE_SLOT = 0;
    private static final int LIGHTMAP_SOURCE_SLOT = 2;
    private static final int BLOCK_ATLAS_TEXTURE_SLOT = 3;

    private static final Int2ObjectOpenHashMap<MeshHandle> HANDLES = new Int2ObjectOpenHashMap<>();
    private static int nextHandle = 1;

    private static final LodArena[] ARENAS = new LodArena[4];

    private static final MappedBuffer CELL_ORIGINS = new MappedBuffer(ORIGIN_SLOTS * ORIGIN_STRIDE_BYTES);
    private static final MappedBuffer FOG_COLOR = new MappedBuffer(4 * Float.BYTES);
    private static final MappedBuffer FOG_PARAMS = new MappedBuffer(4 * Float.BYTES);
    private static final ByteBuffer BATCH_COMMANDS = MemoryUtil.memAlloc(MAX_BATCH_CELLS * INDIRECT_COMMAND_BYTES);
    private static final long BATCH_COMMANDS_PTR = MemoryUtil.memAddress0(BATCH_COMMANDS);
    private static final ByteBuffer BATCH_AABBS = MemoryUtil.memAlloc(MAX_BATCH_CELLS * LodCulling.AABB_STRIDE_BYTES);
    private static final long BATCH_AABBS_PTR = MemoryUtil.memAddress0(BATCH_AABBS);
    private static final Matrix4f CULL_MATRIX_SCRATCH = new Matrix4f();

    static {
        MemoryUtil.memSet(CELL_ORIGINS.ptr, 0, ORIGIN_SLOTS * ORIGIN_STRIDE_BYTES);
        writeFogUniforms();

        HookRegistry.register(new CalderaBridge());
    }

    @ListenedEvent
    public void onFrameBeginBefore(FrameBeginBeforeEvent event) {
        beginLodCollection();
    }

    private static boolean arenaUploadsPending;

    private static boolean indexBoundsWarned = false;
    private static boolean chunkOverflowWarned = false;

    private static volatile float clipDistance = 0.0f;

    private static volatile float fogStartBlocks = Float.MAX_VALUE;
    private static volatile float fogEndBlocks = Float.MAX_VALUE;
    private static volatile float fadeBandStart = 0.0f;
    private static volatile float fadeBandEnd = 0.0f;
    private static volatile float fogRed = 0.0f;
    private static volatile float fogGreen = 0.0f;
    private static volatile float fogBlue = 0.0f;

    private static boolean lodSessionActive;
    private static boolean pyramidReady;

    private static GraphicsPipeline batchPipeline;

    private static boolean batchTexturesSaved;
    private static boolean batchAtlasSaved;
    private static boolean batchWaterStateSaved;

    private static VulkanImage batchPrevSlot0;
    private static VulkanImage batchPrevSlot3;

    private static boolean batchPrevDepthTest;
    private static boolean batchPrevDepthMask;
    private static boolean batchPrevBlendEnabled;

    private static int batchPrevSrcRgb;
    private static int batchPrevDstRgb;
    private static int batchPrevSrcAlpha;
    private static int batchPrevDstAlpha;
    private static int batchPrevBlendOp;
    private static int batchPrevBlendOpRgb;
    private static int batchPrevBlendOpAlpha;
    private static int batchBoundArena = -1;

    private record PendingCell(int handle, double originX, double originZ) {}

    @SuppressWarnings("unchecked")
    private static final List<PendingCell>[] PENDING_BY_PASS = new ArrayList[NUM_PASSES];
    static {
        for (int i = 0; i < NUM_PASSES; i++) {
            PENDING_BY_PASS[i] = new ArrayList<>();
        }
    }

    private static final class CulledChunk {
        int arena = -1;
        int cellsStart;
        int cellCount;
        long dstOffset;
        long countOffset;
    }

    private static final class CulledPassData {
        IndirectBuffer srcCommands;
        IndirectBuffer dstCommands;
        StorageBuffer countBuffer;

        final List<CulledChunk> chunks = new ArrayList<>();
        final List<PendingCell> cellsAtCullTime = new ArrayList<>();
    }

    private static CulledPassData[][] culledData; // [frame][pass]
    private static int lastCulledFrame = -1;

    private static double frameCamX;
    private static double frameCamY;
    private static double frameCamZ;
    private static boolean frameCullEnabled;

    private CalderaBridge() {
    }

    private record MeshHandle(int arena, int indexCount, int firstIndex, int vertexOffset,
                              float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
                              int surfaceIndexCount, int surfaceNonDownIndexCount, int maxDownY) {
    }

    private static volatile boolean drawCaves = true;

    private static final class LodArena {
        final AreaBuffer vertexBuffer;
        final AreaBuffer indexBuffer;
        final int vertexSize;

        LodArena(int vertexSize, int vertexBytes, int indexBytes) {
            this.vertexSize = vertexSize;
            this.vertexBuffer = new AreaBuffer(AreaBuffer.Usage.VERTEX, vertexBytes / vertexSize, vertexSize);
            this.indexBuffer = new AreaBuffer(AreaBuffer.Usage.INDEX, indexBytes / ARENA_INDEX_SIZE_BYTES, ARENA_INDEX_SIZE_BYTES);
        }
    }

    public static MappedBuffer getCellOrigins() {
        return CELL_ORIGINS;
    }

    public static MappedBuffer getFogColor() {
        return FOG_COLOR;
    }

    public static MappedBuffer getFogParams() {
        return FOG_PARAMS;
    }

    private static final int OCCLUSION_REFRESH_INTERVAL = 4;
    private static long occlusionFrameCounter;
    private static boolean occlusionRefreshFrame;

    public static boolean isOcclusionRefreshFrame() {
        return occlusionRefreshFrame;
    }

    public static void onFrameBegin(VkCommandBuffer commandBuffer) {
        DepthSnapshot.beginFrame();
        DepthOcclusion.refresh();

        boolean refreshFrame = (occlusionFrameCounter++ % OCCLUSION_REFRESH_INTERVAL) == 0;
        occlusionRefreshFrame = refreshFrame;

        if (commandBuffer == null) {
            pyramidReady = false;
            return;
        }
        if (WorldRenderer.getInstance() == null || Minecraft.getInstance().level == null) {
            pyramidReady = false;
            return;
        }

        boolean snapshotWanted = Initializer.CONFIG.lodDepthSnapshot;
        boolean cullWanted = lodSessionActive
                && Initializer.CONFIG.lodGpuCulling
                && Initializer.CONFIG.indirectDraw
                && DeviceManager.supportsFastIndirectDraw()
                && LodCulling.isAvailable();

        if (!snapshotWanted && !cullWanted) {
            pyramidReady = false;
            return;
        }

        if (!refreshFrame) {
            pyramidReady = cullWanted && HiZPyramid.isReady();
            return;
        }

        boolean built;
        try {
            built = HiZPyramid.build(commandBuffer);
        } catch (Throwable t) {
            built = false;
            Initializer.LOGGER.error("[Caldera] HiZ pyramid build failed", t);
        }
        pyramidReady = built && cullWanted;

        if (built && snapshotWanted) {
            try {
                DepthSnapshot.record(commandBuffer);
            } catch (Throwable t) {
                Initializer.LOGGER.error("[Caldera] depth snapshot record failed", t);
            }
        }
    }

    public static boolean occlusionAvailable() {
        try {
            return DepthSnapshot.available();
        } catch (Throwable t) {
            return false;
        }
    }

    public static int occlusionGridWidth() {
        try {
            return DepthSnapshot.gridWidth();
        } catch (Throwable t) {
            return 0;
        }
    }

    public static int occlusionGridHeight() {
        try {
            return DepthSnapshot.gridHeight();
        } catch (Throwable t) {
            return 0;
        }
    }

    public static float[] occlusionDepthGrid() {
        try {
            return DepthSnapshot.depthGrid();
        } catch (Throwable t) {
            return null;
        }
    }

    public static float[] occlusionViewProj() {
        try {
            return DepthSnapshot.viewProj();
        } catch (Throwable t) {
            return null;
        }
    }

    public static double[] occlusionCameraPos() {
        try {
            return DepthSnapshot.cameraPos();
        } catch (Throwable t) {
            return null;
        }
    }

    private static LodArena arenaFor(int pass) {
        LodArena arena = ARENAS[pass];
        if (arena == null) {
            boolean textured = pass == PASS_TEX_OPAQUE || pass == PASS_TEX_WATER;
            boolean water = pass == PASS_FLAT_WATER || pass == PASS_TEX_WATER;

            int vertexSize = textured ? INTERNAL_TEXTURED_VERTEX_SIZE_BYTES : INTERNAL_VERTEX_SIZE_BYTES;
            int vertexBytes = water ? WATER_ARENA_VERTEX_BYTES : OPAQUE_ARENA_VERTEX_BYTES;
            int indexBytes = water ? WATER_ARENA_INDEX_BYTES : OPAQUE_ARENA_INDEX_BYTES;

            arena = new LodArena(vertexSize, vertexBytes, indexBytes);

            ARENAS[pass] = arena;
        }
        return arena;
    }

    public static boolean isReady() {
        try {
            return Renderer.getInstance() != null && Renderer.isRecording();
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setClipDistance(float blocks) {
        clipDistance = blocks > 0.0f ? blocks : 0.0f;
    }

    public static void setLodFog(float startBlocks, float endBlocks, float r, float g, float b) {
        fogStartBlocks = startBlocks;
        fogEndBlocks = endBlocks;
        fogRed = r;
        fogGreen = g;
        fogBlue = b;
    }

    public static void setDrawCaves(boolean value) {
        drawCaves = value;
    }

    public static void setLodFadeBand(float startBlocks, float endBlocks) {
        fadeBandStart = startBlocks;
        fadeBandEnd = endBlocks;
    }

    public static void setSurfaceIndexCount(int handle, int surfaceIndexCount) {
        MeshHandle mesh = HANDLES.get(handle);
        if (mesh == null)
            return;

        int clamped = Math.max(0, Math.min(surfaceIndexCount, mesh.indexCount()));

        HANDLES.put(handle, new MeshHandle(mesh.arena(), mesh.indexCount(), mesh.firstIndex(), mesh.vertexOffset(),
                mesh.minX(), mesh.minY(), mesh.minZ(), mesh.maxX(), mesh.maxY(), mesh.maxZ(), clamped,
                Math.min(mesh.surfaceNonDownIndexCount(), clamped), mesh.maxDownY()));
    }

    public static void setLodIndexRanges(int handle, int surfaceNonDownIndexCount, int maxDownY) {
        MeshHandle mesh = HANDLES.get(handle);
        if (mesh == null)
            return;

        int clamped = Math.max(0, Math.min(surfaceNonDownIndexCount, mesh.surfaceIndexCount()));
        HANDLES.put(handle, new MeshHandle(mesh.arena(), mesh.indexCount(), mesh.firstIndex(), mesh.vertexOffset(),
                mesh.minX(), mesh.minY(), mesh.minZ(), mesh.maxX(), mesh.maxY(), mesh.maxZ(),
                mesh.surfaceIndexCount(), clamped, maxDownY));
    }

    public static int uploadMesh(ByteBuffer vertices, int vertexCount, ByteBuffer indices, int indexCount, boolean intIndices) {
        try {
            return uploadMeshInternal(vertices, vertexCount, indices, indexCount, intIndices, PASS_FLAT_OPAQUE);
        } catch (Throwable t) {
            Initializer.LOGGER.error("[Caldera] uploadMesh failed", t);
            return 0;
        }
    }

    public static void drawMesh(int handle, double cellOriginX, double cellOriginZ) {
        try {
            drawMeshInternal(handle, cellOriginX, cellOriginZ);
        } catch (Throwable t) {
            Initializer.LOGGER.error("[Caldera] drawMesh failed", t);
        }
    }

    public static void freeMesh(int handle) {
        try {
            MeshHandle mesh = HANDLES.remove(handle);
            if (mesh != null) {
                LodArena arena = ARENAS[mesh.arena()];
                if (arena != null) {
                    MemoryManager.getInstance().addToFreeSegment(arena.vertexBuffer, mesh.vertexOffset());
                    MemoryManager.getInstance().addToFreeSegment(arena.indexBuffer, mesh.firstIndex());
                }
            }
        } catch (Throwable t) {
            Initializer.LOGGER.error("[Caldera] freeMesh failed", t);
        }
    }

    public static int uploadTexturedMesh(ByteBuffer vertices, int vertexCount, ByteBuffer indices, int indexCount, boolean intIndices) {
        try {
            return uploadTexturedMeshInternal(vertices, vertexCount, indices, indexCount, intIndices, PASS_TEX_OPAQUE);
        } catch (Throwable t) {
            Initializer.LOGGER.error("[Caldera] uploadTexturedMesh failed", t);
            return 0;
        }
    }

    public static void drawTexturedMesh(int handle, double cellOriginX, double cellOriginZ) {
        try {
            drawTexturedMeshInternal(handle, cellOriginX, cellOriginZ);
        } catch (Throwable t) {
            Initializer.LOGGER.error("[Caldera] drawTexturedMesh failed", t);
        }
    }

    public static int uploadTexturedWaterMesh(ByteBuffer vertices, int vertexCount, ByteBuffer indices, int indexCount, boolean intIndices) {
        try {
            return uploadTexturedMeshInternal(vertices, vertexCount, indices, indexCount, intIndices, PASS_TEX_WATER);
        } catch (Throwable t) {
            Initializer.LOGGER.error("[Caldera] uploadTexturedWaterMesh failed", t);
            return 0;
        }
    }

    public static void drawTexturedWaterMesh(int handle, double cellOriginX, double cellOriginZ) {
        try {
            drawTexturedWaterMeshInternal(handle, cellOriginX, cellOriginZ);
        } catch (Throwable t) {
            Initializer.LOGGER.error("[Caldera] drawTexturedWaterMesh failed", t);
        }
    }

    public static int uploadWaterMesh(ByteBuffer vertices, int vertexCount, ByteBuffer indices, int indexCount, boolean intIndices) {
        try {
            return uploadMeshInternal(vertices, vertexCount, indices, indexCount, intIndices, PASS_FLAT_WATER);
        } catch (Throwable t) {
            Initializer.LOGGER.error("[Caldera] uploadWaterMesh failed", t);
            return 0;
        }
    }

    public static void drawWaterMesh(int handle, double cellOriginX, double cellOriginZ) {
        try {
            drawWaterMeshInternal(handle, cellOriginX, cellOriginZ);
        } catch (Throwable t) {
            Initializer.LOGGER.error("[Caldera] drawWaterMesh failed", t);
        }
    }

    public static void beginLodCollection() {
        for (List<PendingCell> list : PENDING_BY_PASS) {
            list.clear();
        }
    }

    public static void collectLodCell(int pass, int handle, double cellOriginX, double cellOriginZ) {
        if (pass < 0 || pass >= NUM_PASSES || !HANDLES.containsKey(handle))
            return;

        PENDING_BY_PASS[pass].add(new PendingCell(handle, cellOriginX, cellOriginZ));
    }

    private static void prepareCulledFrame() {
        int framesNum = Renderer.getFramesNum();

        if (culledData == null || culledData.length != framesNum) {
            culledData = new CulledPassData[framesNum][NUM_PASSES];

            for (int f = 0; f < framesNum; f++) {
                for (int p = 0; p < NUM_PASSES; p++) {
                    CulledPassData d = new CulledPassData();

                    d.srcCommands = new IndirectBuffer(INDIRECT_PASS_BUFFER_BYTES, MemoryTypes.HOST_MEM);
                    d.dstCommands = new IndirectBuffer(INDIRECT_PASS_BUFFER_BYTES, MemoryTypes.HOST_MEM);
                    d.countBuffer = new StorageBuffer(COUNT_PASS_BUFFER_BYTES, MemoryTypes.HOST_MEM);

                    culledData[f][p] = d;
                }
            }

            lastCulledFrame = -1;
        }

        int frame = Renderer.getCurrentFrame();

        if (frame != lastCulledFrame) {
            for (int p = 0; p < NUM_PASSES; p++) {
                CulledPassData d = culledData[frame][p];

                d.srcCommands.reset();
                d.dstCommands.reset();
                d.countBuffer.reset();
                d.chunks.clear();
                d.cellsAtCullTime.clear();
            }

            lastCulledFrame = frame;
        }
    }

    public static void cullLodFrame(VkCommandBuffer commandBuffer) {
        if (commandBuffer == null || !isReady()) {
            return;
        }

        Renderer renderer = Renderer.getInstance();
        if (renderer.getBoundRenderPass() != null) {
            Initializer.LOGGER.error("[Caldera] bad caldera render call");
            return;
        }

        if (arenaUploadsPending) {
            UploadManager.INSTANCE.submitUploads();
            arenaUploadsPending = false;
        }

        prepareCulledFrame();

        Vec3 liveCam = WorldRenderer.getCameraPos();
        frameCamX = liveCam != null ? liveCam.x() : 0.0;
        frameCamY = liveCam != null ? liveCam.y() : 0.0;
        frameCamZ = liveCam != null ? liveCam.z() : 0.0;

        frameCullEnabled = Initializer.CONFIG.lodGpuCulling
                && Initializer.CONFIG.indirectDraw
                && DeviceManager.supportsFastIndirectDraw()
                && LodCulling.isAvailable();

        if (frameCullEnabled) {
            FloatBuffer mvpSrc = VRenderSystem.getExternalLodMVP().buffer.asFloatBuffer();
            mvpSrc.position(0);
            CULL_MATRIX_SCRATCH.set(mvpSrc);
        }

        int frame = Renderer.getCurrentFrame();
        for (int pass = 0; pass < NUM_PASSES; pass++) {
            cullPass(commandBuffer, pass, culledData[frame][pass]);
        }
    }

    private static void cullPass(VkCommandBuffer commandBuffer, int pass, CulledPassData data) {
        List<PendingCell> pending = PENDING_BY_PASS[pass];
        if (pending.isEmpty()) {
            return;
        }

        int totalCells = pending.size();
        int numChunks = (totalCells + MAX_BATCH_CELLS - 1) / MAX_BATCH_CELLS;
        if (numChunks > MAX_CHUNKS_PER_PASS) {
            if (!chunkOverflowWarned) {
                chunkOverflowWarned = true;
                Initializer.LOGGER.error("[Caldera] pass {} has {} visible LOD cells, exceeding the "
                                + "MAX_CHUNKS_PER_PASS * MAX_BATCH_CELLS budget ({}). Extra cells are dropped "
                                + "this frame; raise MAX_CHUNKS_PER_PASS if this happens routinely.",
                        pass, totalCells, MAX_CHUNKS_PER_PASS * MAX_BATCH_CELLS);
            }
            numChunks = MAX_CHUNKS_PER_PASS;
        }

        data.cellsAtCullTime.addAll(pending);

        for (int chunkIndex = 0; chunkIndex < numChunks; chunkIndex++) {
            int start = chunkIndex * MAX_BATCH_CELLS;
            int end = Math.min(start + MAX_BATCH_CELLS, totalCells);
            int count = end - start;

            if (count <= 0)
                break;

            int arena = HANDLES.get(pending.get(start).handle()).arena();

            BATCH_COMMANDS.clear();
            BATCH_AABBS.clear();

            for (int i = 0; i < count; i++) {
                PendingCell cell = pending.get(start + i);
                MeshHandle mesh = HANDLES.get(cell.handle());

                if (mesh == null)
                    continue;

                int drawIndexCount = drawCaves ? mesh.indexCount()
                        : (frameCamY >= mesh.maxDownY() ? mesh.surfaceNonDownIndexCount() : mesh.surfaceIndexCount());

                int slot = i + 1;

                long cmdPtr = BATCH_COMMANDS_PTR + (long) i * INDIRECT_COMMAND_BYTES;

                MemoryUtil.memPutInt(cmdPtr, drawIndexCount);
                MemoryUtil.memPutInt(cmdPtr + 4, 1);
                MemoryUtil.memPutInt(cmdPtr + 8, mesh.firstIndex());
                MemoryUtil.memPutInt(cmdPtr + 12, mesh.vertexOffset());
                MemoryUtil.memPutInt(cmdPtr + 16, slot);

                float baseX = (float) (cell.originX() - frameCamX);
                float baseZ = (float) (cell.originZ() - frameCamZ);

                long aabbPtr = BATCH_AABBS_PTR + (long) i * LodCulling.AABB_STRIDE_BYTES;

                MemoryUtil.memPutFloat(aabbPtr, baseX + mesh.minX());
                MemoryUtil.memPutFloat(aabbPtr + 4, (float) (mesh.minY() - frameCamY));
                MemoryUtil.memPutFloat(aabbPtr + 8, baseZ + mesh.minZ());
                MemoryUtil.memPutFloat(aabbPtr + 12, 0.0f);
                MemoryUtil.memPutFloat(aabbPtr + 16, baseX + mesh.maxX());
                MemoryUtil.memPutFloat(aabbPtr + 20, (float) (mesh.maxY() - frameCamY));
                MemoryUtil.memPutFloat(aabbPtr + 24, baseZ + mesh.maxZ());
                MemoryUtil.memPutFloat(aabbPtr + 28, 0.0f);
            }

            BATCH_COMMANDS.position(0).limit(count * INDIRECT_COMMAND_BYTES);
            long srcOffset = data.srcCommands.getOffset();
            data.srcCommands.recordCopyCmd(BATCH_COMMANDS);
            BATCH_COMMANDS.clear();

            long dstOffset = data.dstCommands.getOffset();
            data.dstCommands.recordCopyCmd(BATCH_COMMANDS); // reserves/advances the same span; content overwritten by cull or below
            long countOffset = data.countBuffer.getOffset();

            boolean culled = false;
            if (frameCullEnabled) {
                BATCH_AABBS.position(0).limit(count * LodCulling.AABB_STRIDE_BYTES);
                long aabbOffset = LodCulling.uploadAabbs(BATCH_AABBS);
                BATCH_AABBS.clear();

                int flags = pyramidReady ? LodCulling.FLAG_OCCLUSION : 0;
                culled = LodCulling.prepareCompact(commandBuffer, LodCulling.getAabbBuffer(), aabbOffset,
                        data.srcCommands, srcOffset, data.dstCommands, dstOffset,
                        data.countBuffer, countOffset, count, CULL_MATRIX_SCRATCH, flags);
            }

            if (!culled)
                data.countBuffer.putInt((int) countOffset, count);

            CulledChunk chunk = new CulledChunk();

            chunk.arena = arena;
            chunk.cellsStart = start;
            chunk.cellCount = count;
            chunk.dstOffset = dstOffset;
            chunk.countOffset = countOffset;

            data.chunks.add(chunk);
        }
    }

    public static boolean beginLodDraw(int pass) {
        try {
            return beginLodDrawInternal(pass);
        } catch (Throwable t) {
            Initializer.LOGGER.error("[Caldera] beginLodDraw failed", t);

            try {
                endLodDrawInternal();
            } catch (Throwable ignored) {}

            return false;
        }
    }

    public static void endLodDraw() {
        try {
            endLodDrawInternal();
        } catch (Throwable t) {
            Initializer.LOGGER.error("[Caldera] endLodDraw failed", t);
        }
    }

    private static boolean beginLodDrawInternal(int pass) {
        if (batchPipeline != null || batchTexturesSaved || batchWaterStateSaved)
            endLodDrawInternal();

        if (!isReady() || pass < 0 || pass >= NUM_PASSES)
            return false;

        int frame = Renderer.getCurrentFrame();
        if (culledData == null || frame != lastCulledFrame)
            return false;

        CulledPassData data = culledData[frame][pass];

        if (data.chunks.isEmpty())
            return false;

        PipelineManager pipelineManager = Renderer.getInstance().getPipelineManager();

        GraphicsPipeline pipeline = switch (pass) {
            case PASS_FLAT_OPAQUE -> pipelineManager.getPipeline(PipelineManager.PIPELINE_EXTERNAL_LOD);
            case PASS_TEX_OPAQUE -> pipelineManager.getPipeline(PipelineManager.PIPELINE_EXTERNAL_LOD_TEXTURED);
            case PASS_FLAT_WATER -> pipelineManager.getPipeline(PipelineManager.PIPELINE_EXTERNAL_LOD_WATER);
            case PASS_TEX_WATER -> pipelineManager.getPipeline(PipelineManager.PIPELINE_EXTERNAL_LOD_WATER_TEXTURED);
            case PASS_FLAT_OPAQUE_SOLID -> pipelineManager.getPipeline(PipelineManager.PIPELINE_EXTERNAL_LOD_SOLID);
            case PASS_TEX_OPAQUE_SOLID -> pipelineManager.getPipeline(PipelineManager.PIPELINE_EXTERNAL_LOD_TEXTURED_SOLID);

            default -> null;
        };

        if (pipeline == null)
            return false;

        boolean textured = pass == PASS_TEX_OPAQUE || pass == PASS_TEX_WATER || pass == PASS_TEX_OPAQUE_SOLID;
        boolean water = pass == PASS_FLAT_WATER || pass == PASS_TEX_WATER;

        writeSharedUniforms();

        batchPrevSlot0 = VTextureSelector.getImage(LIGHTMAP_TEXTURE_SLOT);
        batchPrevSlot3 = textured ? VTextureSelector.getImage(BLOCK_ATLAS_TEXTURE_SLOT) : null;
        batchTexturesSaved = true;
        batchAtlasSaved = textured;
        bindLightmap();

        if (textured && !bindAtlas()) {
            endLodDrawInternal();
            return false;
        }

        if (water) {
            batchPrevDepthTest = VRenderSystem.depthTest;
            batchPrevDepthMask = VRenderSystem.depthMask;
            PipelineState.BlendInfo bi = PipelineState.blendInfo;
            batchPrevBlendEnabled = bi.enabled;
            batchPrevSrcRgb = bi.srcRgbFactor;
            batchPrevDstRgb = bi.dstRgbFactor;
            batchPrevSrcAlpha = bi.srcAlphaFactor;
            batchPrevDstAlpha = bi.dstAlphaFactor;
            batchPrevBlendOp = bi.blendOp;
            batchPrevBlendOpRgb = bi.blendOpRgb;
            batchPrevBlendOpAlpha = bi.blendOpAlpha;
            batchWaterStateSaved = true;

            VRenderSystem.depthTest = true;
            VRenderSystem.depthMask = false;
            bi.enabled = true;
            bi.srcRgbFactor = VK10.VK_BLEND_FACTOR_SRC_ALPHA;
            bi.dstRgbFactor = VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            bi.srcAlphaFactor = VK10.VK_BLEND_FACTOR_ONE;
            bi.dstAlphaFactor = VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            bi.blendOp = bi.blendOpRgb = bi.blendOpAlpha = VK10.VK_BLEND_OP_ADD;
        }

        Renderer renderer = Renderer.getInstance();
        if (!renderer.bindGraphicsPipeline(pipeline)) {
            endLodDrawInternal();
            return false;
        }

        batchBoundArena = -1;
        batchPipeline = pipeline;

        for (CulledChunk chunk : data.chunks)
            drawChunk(pipeline, data, chunk);

        return true;
    }

    private static void drawChunk(GraphicsPipeline pipeline, CulledPassData data, CulledChunk chunk) {
        if (chunk.arena != batchBoundArena) {
            LodArena arena = ARENAS[chunk.arena];

            if (arena == null)
                return;

            bindArena(arena);
            batchBoundArena = chunk.arena;
        }

        for (int i = 0; i < chunk.cellCount; i++) {
            PendingCell cell = data.cellsAtCullTime.get(chunk.cellsStart + i);

            int slot = i + 1;
            int originOffset = slot * ORIGIN_STRIDE_BYTES;

            CELL_ORIGINS.putFloat(originOffset, (float) (cell.originX() - frameCamX));
            CELL_ORIGINS.putFloat(originOffset + 4, 0.0f);
            CELL_ORIGINS.putFloat(originOffset + 8, (float) (cell.originZ() - frameCamZ));
            CELL_ORIGINS.putFloat(originOffset + 12, 0.0f);
        }

        CELL_ORIGINS.buffer.limit((chunk.cellCount + 1) * ORIGIN_STRIDE_BYTES);
        Renderer.getInstance().uploadAndBindUBOs(pipeline);
        CELL_ORIGINS.buffer.limit(ORIGIN_SLOTS * ORIGIN_STRIDE_BYTES);

        VK12.vkCmdDrawIndexedIndirectCount(Renderer.getCommandBuffer(),
                data.dstCommands.getId(), chunk.dstOffset,
                data.countBuffer.getId(), chunk.countOffset,
                chunk.cellCount, INDIRECT_COMMAND_BYTES);
    }

    private static void bindArena(LodArena arena) {
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK10.nvkCmdBindVertexBuffers(commandBuffer, 0, 1, stack.npointer(arena.vertexBuffer.getId()), stack.npointer(0L));
        }
        VK10.vkCmdBindIndexBuffer(commandBuffer, arena.indexBuffer.getId(), 0, VK10.VK_INDEX_TYPE_UINT32);
    }

    private static void endLodDrawInternal() {
        batchBoundArena = -1;
        batchPipeline = null;
        if (batchWaterStateSaved) {
            batchWaterStateSaved = false;
            VRenderSystem.depthTest = batchPrevDepthTest;
            VRenderSystem.depthMask = batchPrevDepthMask;
            PipelineState.BlendInfo bi = PipelineState.blendInfo;
            bi.enabled = batchPrevBlendEnabled;
            bi.srcRgbFactor = batchPrevSrcRgb;
            bi.dstRgbFactor = batchPrevDstRgb;
            bi.srcAlphaFactor = batchPrevSrcAlpha;
            bi.dstAlphaFactor = batchPrevDstAlpha;
            bi.blendOp = batchPrevBlendOp;
            bi.blendOpRgb = batchPrevBlendOpRgb;
            bi.blendOpAlpha = batchPrevBlendOpAlpha;
        }
        if (batchTexturesSaved) {
            batchTexturesSaved = false;
            VTextureSelector.bindTexture(LIGHTMAP_TEXTURE_SLOT, batchPrevSlot0);
            if (batchAtlasSaved) {
                batchAtlasSaved = false;
                VTextureSelector.bindTexture(BLOCK_ATLAS_TEXTURE_SLOT, batchPrevSlot3);
            }
            batchPrevSlot0 = null;
            batchPrevSlot3 = null;
        }
    }

    private static int uploadMeshInternal(ByteBuffer vertices, int vertexCount, ByteBuffer indices, int indexCount, boolean intIndices, int pass) {
        if (vertices == null || indices == null || vertexCount <= 0 || indexCount <= 0 || indexCount % 3 != 0) {
            return 0;
        }

        ByteBuffer vertexSrc = vertices.duplicate();
        vertexSrc.order(ByteOrder.LITTLE_ENDIAN);
        if (vertexSrc.remaining() < vertexCount * INPUT_VERTEX_SIZE_BYTES) {
            return 0;
        }

        int indexSize = intIndices ? 4 : 2;
        int indexBytesNeeded = indexCount * indexSize;
        ByteBuffer indexSrc = indices.duplicate();
        if (indexSrc.remaining() < indexBytesNeeded) {
            return 0;
        }

        ByteBuffer indexScan = indexSrc.duplicate();
        indexScan.order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < indexCount; i++) {
            int idx = intIndices ? indexScan.getInt() : (indexScan.getShort() & 0xFFFF);
            if (idx < 0 || idx >= vertexCount) {
                if (!indexBoundsWarned) {
                    indexBoundsWarned = true;
                    Initializer.LOGGER.error("[Caldera] uploadMesh: index {} out of bounds (vertexCount={})", idx, vertexCount);
                }
                return 0;
            }
        }

        int vertexOffset;
        int firstIndex;

        float boundMinX = Float.POSITIVE_INFINITY;
        float boundMinY = Float.POSITIVE_INFINITY;
        float boundMinZ = Float.POSITIVE_INFINITY;
        float boundMaxX = Float.NEGATIVE_INFINITY;
        float boundMaxY = Float.NEGATIVE_INFINITY;
        float boundMaxZ = Float.NEGATIVE_INFINITY;

        ByteBuffer internalVertices = MemoryUtil.memAlloc(vertexCount * INTERNAL_VERTEX_SIZE_BYTES);
        try {
            internalVertices.order(ByteOrder.LITTLE_ENDIAN);

            for (int i = 0; i < vertexCount; i++) {
                float x = vertexSrc.getFloat();
                float y = vertexSrc.getFloat();
                float z = vertexSrc.getFloat();
                int c = vertexSrc.getInt();
                boundMinX = Math.min(boundMinX, x);
                boundMinY = Math.min(boundMinY, y);
                boundMinZ = Math.min(boundMinZ, z);
                boundMaxX = Math.max(boundMaxX, x);
                boundMaxY = Math.max(boundMaxY, y);
                boundMaxZ = Math.max(boundMaxZ, z);
                int light = (c >>> 24) & 0xFF;
                byte r = (byte) ((c >> 16) & 0xFF);
                byte g = (byte) ((c >>  8) & 0xFF);
                byte b = (byte) ( c        & 0xFF);

                internalVertices.putShort((short) quantizeXZ(x));
                internalVertices.putShort((short) quantizeY(y));
                internalVertices.putShort((short) quantizeXZ(z));
                internalVertices.putShort((short) light);
                internalVertices.put(r).put(g).put(b).put((byte) 0xFF);
            }
            internalVertices.position(0);

            ByteBuffer internalIndices = MemoryUtil.memAlloc(indexCount * ARENA_INDEX_SIZE_BYTES);
            try {
                writeArenaIndices(internalIndices, indexSrc, indexCount, intIndices);
                internalIndices.position(0);

                LodArena arena = arenaFor(pass);
                vertexOffset = arena.vertexBuffer.upload(internalVertices, -1, new DrawBuffers.DrawParameters()).getOffset() / INTERNAL_VERTEX_SIZE_BYTES;
                firstIndex = arena.indexBuffer.upload(internalIndices, -1, new DrawBuffers.DrawParameters()).getOffset() / ARENA_INDEX_SIZE_BYTES;
            } finally {
                MemoryUtil.memFree(internalIndices);
            }
        } finally {
            MemoryUtil.memFree(internalVertices);
        }
        arenaUploadsPending = true;

        int handle = nextHandle++;
        if (nextHandle == 0) {
            nextHandle = 1;
        }
        HANDLES.put(handle, new MeshHandle(pass, indexCount, firstIndex, vertexOffset,
                boundMinX, boundMinY, boundMinZ, boundMaxX, boundMaxY, boundMaxZ, indexCount, indexCount, Integer.MIN_VALUE));
        lodSessionActive = true;
        return handle;
    }

    private static void writeArenaIndices(ByteBuffer dst, ByteBuffer src, int indexCount, boolean intIndices) {
        dst.order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer indices = src.duplicate();
        indices.order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < indexCount; i++) {
            dst.putInt(intIndices ? indices.getInt() : (indices.getShort() & 0xFFFF));
        }
    }

    private static void drawMeshInternal(int handle, double cellOriginX, double cellOriginZ) {
        drawSingleMeshImmediate(PASS_FLAT_OPAQUE, handle, cellOriginX, cellOriginZ);
    }

    private static int uploadTexturedMeshInternal(ByteBuffer vertices, int vertexCount, ByteBuffer indices, int indexCount, boolean intIndices, int pass) {
        if (vertices == null || indices == null || vertexCount <= 0 || indexCount <= 0 || indexCount % 3 != 0) {
            return 0;
        }

        ByteBuffer vertexSrc = vertices.duplicate();
        vertexSrc.order(ByteOrder.LITTLE_ENDIAN);
        if (vertexSrc.remaining() < vertexCount * INPUT_TEXTURED_VERTEX_SIZE_BYTES) {
            return 0;
        }

        int indexSize = intIndices ? 4 : 2;
        int indexBytesNeeded = indexCount * indexSize;
        ByteBuffer indexSrc = indices.duplicate();
        if (indexSrc.remaining() < indexBytesNeeded) {
            return 0;
        }

        ByteBuffer indexScan = indexSrc.duplicate();
        indexScan.order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < indexCount; i++) {
            int idx = intIndices ? indexScan.getInt() : (indexScan.getShort() & 0xFFFF);
            if (idx < 0 || idx >= vertexCount) {
                if (!indexBoundsWarned) {
                    indexBoundsWarned = true;
                    Initializer.LOGGER.error("[Caldera] uploadTexturedMesh: index {} out of bounds (vertexCount={})", idx, vertexCount);
                }
                return 0;
            }
        }

        int vertexOffset;
        int firstIndex;

        float boundMinX = Float.POSITIVE_INFINITY;
        float boundMinY = Float.POSITIVE_INFINITY;
        float boundMinZ = Float.POSITIVE_INFINITY;
        float boundMaxX = Float.NEGATIVE_INFINITY;
        float boundMaxY = Float.NEGATIVE_INFINITY;
        float boundMaxZ = Float.NEGATIVE_INFINITY;

        ByteBuffer internalVertices = MemoryUtil.memAlloc(vertexCount * INTERNAL_TEXTURED_VERTEX_SIZE_BYTES);
        try {
            internalVertices.order(ByteOrder.LITTLE_ENDIAN);

            for (int i = 0; i < vertexCount; i++) {
                float x = vertexSrc.getFloat();
                float y = vertexSrc.getFloat();
                float z = vertexSrc.getFloat();
                int c = vertexSrc.getInt();
                boundMinX = Math.min(boundMinX, x);
                boundMinY = Math.min(boundMinY, y);
                boundMinZ = Math.min(boundMinZ, z);
                boundMaxX = Math.max(boundMaxX, x);
                boundMaxY = Math.max(boundMaxY, y);
                boundMaxZ = Math.max(boundMaxZ, z);
                float u0 = vertexSrc.getFloat();
                float v0 = vertexSrc.getFloat();
                float u1 = vertexSrc.getFloat();
                float v1 = vertexSrc.getFloat();
                float tileU = vertexSrc.getFloat();
                float tileV = vertexSrc.getFloat();

                int light = (c >>> 24) & 0xFF;
                byte r = (byte) ((c >> 16) & 0xFF);
                byte g = (byte) ((c >>  8) & 0xFF);
                byte b = (byte) ( c        & 0xFF);

                internalVertices.putShort((short) quantizeXZ(x));
                internalVertices.putShort((short) quantizeY(y));
                internalVertices.putShort((short) quantizeXZ(z));
                internalVertices.putShort((short) light);
                internalVertices.put(r).put(g).put(b).put((byte) 0xFF);
                internalVertices.putShort(unorm16(u0));
                internalVertices.putShort(unorm16(v0));
                internalVertices.putShort(unorm16(u1));
                internalVertices.putShort(unorm16(v1));
                internalVertices.putShort((short) (Math.round(tileU) + Y_BIAS));
                internalVertices.putShort((short) (Math.round(tileV) + Y_BIAS));
            }
            internalVertices.position(0);

            ByteBuffer internalIndices = MemoryUtil.memAlloc(indexCount * ARENA_INDEX_SIZE_BYTES);
            try {
                writeArenaIndices(internalIndices, indexSrc, indexCount, intIndices);
                internalIndices.position(0);

                LodArena arena = arenaFor(pass);
                vertexOffset = arena.vertexBuffer.upload(internalVertices, -1, new DrawBuffers.DrawParameters()).getOffset() / INTERNAL_TEXTURED_VERTEX_SIZE_BYTES;
                firstIndex = arena.indexBuffer.upload(internalIndices, -1, new DrawBuffers.DrawParameters()).getOffset() / ARENA_INDEX_SIZE_BYTES;
            } finally {
                MemoryUtil.memFree(internalIndices);
            }
        } finally {
            MemoryUtil.memFree(internalVertices);
        }
        arenaUploadsPending = true;

        int handle = nextHandle++;
        if (nextHandle == 0) {
            nextHandle = 1;
        }
        HANDLES.put(handle, new MeshHandle(pass, indexCount, firstIndex, vertexOffset,
                boundMinX, boundMinY, boundMinZ, boundMaxX, boundMaxY, boundMaxZ, indexCount, indexCount, Integer.MIN_VALUE));
        lodSessionActive = true;
        return handle;
    }

    private static void drawTexturedMeshInternal(int handle, double cellOriginX, double cellOriginZ) {
        drawSingleMeshImmediate(PASS_TEX_OPAQUE, handle, cellOriginX, cellOriginZ);
    }

    private static void drawTexturedWaterMeshInternal(int handle, double cellOriginX, double cellOriginZ) {
        drawSingleMeshImmediate(PASS_TEX_WATER, handle, cellOriginX, cellOriginZ);
    }

    private static void drawWaterMeshInternal(int handle, double cellOriginX, double cellOriginZ) {
        drawSingleMeshImmediate(PASS_FLAT_WATER, handle, cellOriginX, cellOriginZ);
    }

    private static void drawSingleMeshImmediate(int pass, int handle, double cellOriginX, double cellOriginZ) {
        if (!beginSingleMeshDraw(pass)) {
            return;
        }
        try {
            MeshHandle mesh = HANDLES.get(handle);
            if (mesh == null) {
                return;
            }
            LodArena arena = ARENAS[mesh.arena()];
            if (arena == null) {
                return;
            }

            Vec3 liveCam = WorldRenderer.getCameraPos();
            double camX = liveCam != null ? liveCam.x() : 0.0;
            double camY = liveCam != null ? liveCam.y() : 0.0;
            double camZ = liveCam != null ? liveCam.z() : 0.0;

            int drawIndexCount = drawCaves ? mesh.indexCount()
                    : (camY >= mesh.maxDownY() ? mesh.surfaceNonDownIndexCount() : mesh.surfaceIndexCount());
            if (drawIndexCount <= 0) {
                return;
            }

            bindArena(arena);
            pushOrigin(batchPipeline, (float) (cellOriginX - camX), (float) (-camY - Y_BIAS), (float) (cellOriginZ - camZ));
            VK10.vkCmdDrawIndexed(Renderer.getCommandBuffer(), drawIndexCount, 1, mesh.firstIndex(), mesh.vertexOffset(), 0);
        } finally {
            endLodDrawInternal();
        }
    }

    private static boolean beginSingleMeshDraw(int pass) {
        if (batchPipeline != null || batchTexturesSaved || batchWaterStateSaved) {
            endLodDrawInternal();
        }
        if (!isReady()) {
            return false;
        }

        PipelineManager pipelineManager = Renderer.getInstance().getPipelineManager();
        GraphicsPipeline pipeline = switch (pass) {
            case PASS_FLAT_OPAQUE -> pipelineManager.getPipeline(PipelineManager.PIPELINE_EXTERNAL_LOD);
            case PASS_TEX_OPAQUE -> pipelineManager.getPipeline(PipelineManager.PIPELINE_EXTERNAL_LOD_TEXTURED);
            case PASS_FLAT_WATER -> pipelineManager.getPipeline(PipelineManager.PIPELINE_EXTERNAL_LOD_WATER);
            case PASS_TEX_WATER -> pipelineManager.getPipeline(PipelineManager.PIPELINE_EXTERNAL_LOD_WATER_TEXTURED);
            default -> null;
        };
        if (pipeline == null) {
            return false;
        }

        boolean textured = pass == PASS_TEX_OPAQUE || pass == PASS_TEX_WATER;
        boolean water = pass == PASS_FLAT_WATER || pass == PASS_TEX_WATER;

        if (arenaUploadsPending) {
            UploadManager.INSTANCE.submitUploads();
            arenaUploadsPending = false;
        }

        writeSharedUniforms();

        batchPrevSlot0 = VTextureSelector.getImage(LIGHTMAP_TEXTURE_SLOT);
        batchPrevSlot3 = textured ? VTextureSelector.getImage(BLOCK_ATLAS_TEXTURE_SLOT) : null;
        batchTexturesSaved = true;
        batchAtlasSaved = textured;
        bindLightmap();

        if (textured && !bindAtlas()) {
            endLodDrawInternal();
            return false;
        }

        if (water) {
            batchPrevDepthTest = VRenderSystem.depthTest;
            batchPrevDepthMask = VRenderSystem.depthMask;
            PipelineState.BlendInfo bi = PipelineState.blendInfo;
            batchPrevBlendEnabled = bi.enabled;
            batchPrevSrcRgb = bi.srcRgbFactor;
            batchPrevDstRgb = bi.dstRgbFactor;
            batchPrevSrcAlpha = bi.srcAlphaFactor;
            batchPrevDstAlpha = bi.dstAlphaFactor;
            batchPrevBlendOp = bi.blendOp;
            batchPrevBlendOpRgb = bi.blendOpRgb;
            batchPrevBlendOpAlpha = bi.blendOpAlpha;
            batchWaterStateSaved = true;

            VRenderSystem.depthTest = true;
            VRenderSystem.depthMask = false;
            bi.enabled = true;
            bi.srcRgbFactor = VK10.VK_BLEND_FACTOR_SRC_ALPHA;
            bi.dstRgbFactor = VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            bi.srcAlphaFactor = VK10.VK_BLEND_FACTOR_ONE;
            bi.dstAlphaFactor = VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            bi.blendOp = bi.blendOpRgb = bi.blendOpAlpha = VK10.VK_BLEND_OP_ADD;
        }

        Renderer renderer = Renderer.getInstance();
        if (!renderer.bindGraphicsPipeline(pipeline)) {
            endLodDrawInternal();
            return false;
        }

        CELL_ORIGINS.buffer.limit(ORIGIN_STRIDE_BYTES);
        renderer.uploadAndBindUBOs(pipeline);
        CELL_ORIGINS.buffer.limit(ORIGIN_SLOTS * ORIGIN_STRIDE_BYTES);

        batchBoundArena = -1;
        batchPipeline = pipeline;
        return true;
    }

    private static void pushOrigin(GraphicsPipeline pipeline, float x, float y, float z) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer pushData = stack.malloc(16);
            pushData.putFloat(0, x);
            pushData.putFloat(4, y);
            pushData.putFloat(8, z);
            pushData.putFloat(12, 0.0f);
            VK10.vkCmdPushConstants(Renderer.getCommandBuffer(), pipeline.getLayout(), VK10.VK_SHADER_STAGE_VERTEX_BIT, 0, pushData);
        }
    }

    private static short unorm16(float value) {
        float clamped = value < 0.0f ? 0.0f : (value > 1.0f ? 1.0f : value);
        return (short) Math.round(clamped * 65535.0f);
    }

    private static int quantizeXZ(float value) {
        long raw = Math.round(value);
        if (raw < 0) {
            raw = 0;
        } else if (raw > POSITION_MAX) {
            raw = POSITION_MAX;
        }
        return (int) raw;
    }

    private static int quantizeY(float value) {
        long raw = Math.round(value) + Y_BIAS;
        if (raw < 0) {
            raw = 0;
        } else if (raw > POSITION_MAX) {
            raw = POSITION_MAX;
        }
        return (int) raw;
    }

    private static void writeSharedUniforms() {
        FloatBuffer mvpSrc = VRenderSystem.getExternalLodMVP().buffer.asFloatBuffer();
        mvpSrc.position(0);
        COMBINED_MATRIX_SCRATCH.set(mvpSrc);

        FloatBuffer combinedDst = ExternalTerrainRenderBridge.getCombinedMatrix().buffer.asFloatBuffer();
        combinedDst.position(0);
        COMBINED_MATRIX_SCRATCH.get(combinedDst);

        float clip = clipDistance;
        MappedBuffer renderParams = ExternalTerrainRenderBridge.getRenderParams();
        renderParams.putFloat(0, 0.0f);
        renderParams.putFloat(4, clip > 0.0f ? clip : 0.0f);
        renderParams.putFloat(8, 0.0f);
        renderParams.putFloat(12, clip > 0.0f ? 1.0f : 0.0f);

        writeFogUniforms();
    }

    private static void writeFogUniforms() {
        MappedBuffer shaderFog = VRenderSystem.getShaderFogColor();
        FOG_COLOR.putFloat(0, shaderFog.getFloat(0));
        FOG_COLOR.putFloat(4, shaderFog.getFloat(4));
        FOG_COLOR.putFloat(8, shaderFog.getFloat(8));
        FOG_COLOR.putFloat(12, 0.0f);

        float start = fogStartBlocks;
        float end = fogEndBlocks;
        boolean validCalderaFog = end > start && end < Float.MAX_VALUE * 0.25f;
        if (!validCalderaFog) {
            if (clipDistance > 0.0f) {
                start = clipDistance;
                end = clipDistance * 3.0f;
            } else {
                start = Float.MAX_VALUE * 0.5f;
                end = Float.MAX_VALUE;
            }
        }
        FOG_PARAMS.putFloat(0, start);
        FOG_PARAMS.putFloat(4, end);
        FOG_PARAMS.putFloat(8, fadeBandStart);
        FOG_PARAMS.putFloat(12, fadeBandEnd);
    }

    private static void bindLightmap() {
        VulkanImage lightmap = VTextureSelector.getImage(LIGHTMAP_SOURCE_SLOT);
        if (lightmap != null) {
            VTextureSelector.bindTexture(LIGHTMAP_TEXTURE_SLOT, lightmap);
        }
    }

    private static boolean bindAtlas() {
        TextureAtlas atlas = Minecraft.getInstance().getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS);
        if (atlas == null) {
            return false;
        }

        GlTexture glTexture = GlTexture.getTexture(atlas.getId());
        VulkanImage atlasImage = glTexture != null ? glTexture.getVulkanImage() : null;
        if (atlasImage == null) {
            return false;
        }

        VTextureSelector.bindTexture(BLOCK_ATLAS_TEXTURE_SLOT, atlasImage);
        return true;
    }
}