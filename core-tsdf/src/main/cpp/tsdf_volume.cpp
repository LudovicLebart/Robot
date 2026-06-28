#include "tsdf_volume.h"
#include "marching_cubes.h"
#include <cmath>
#include <algorithm>
#include <android/log.h>

#define TAG "TsdfVolume"

TsdfVolume::TsdfVolume(int /*sx*/, int /*sy*/, int /*sz*/,
                       float voxelSize, float truncation)
    : voxelSize_(voxelSize), truncation_(truncation)
{}

TsdfVolume::~TsdfVolume() {
    reset();
}

void TsdfVolume::reset() {
    for (auto& [k, b] : blocks_) delete b;
    blocks_.clear();
    meshCache_.clear();
    frameCount_ = 0;
}

TsdfBlock* TsdfVolume::getOrCreate(const BlockKey& key) {
    auto it = blocks_.find(key);
    if (it != blocks_.end()) return it->second;
    TsdfBlock* b;
    try {
        b = new TsdfBlock{};
    } catch (const std::bad_alloc&) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "getOrCreate: out of memory (%zu blocks)", blocks_.size());
        return nullptr;
    }
    blocks_[key] = b;
    return b;
}

void TsdfVolume::markDirtyNeighbor(int bx, int by, int bz) {
    auto it = blocks_.find({bx, by, bz});
    if (it != blocks_.end()) it->second->dirty = true;
}

float TsdfVolume::tsdfAt(int gx, int gy, int gz) const {
    int bx = toBlock(gx), by = toBlock(gy), bz = toBlock(gz);
    auto it = blocks_.find({bx, by, bz});
    if (it == blocks_.end()) return 1.0f;
    int lx = gx - bx * BLOCK_SIZE;
    int ly = gy - by * BLOCK_SIZE;
    int lz = gz - bz * BLOCK_SIZE;
    const auto& v = it->second->at(lx, ly, lz);
    return (v.weight >= 5.0f) ? v.tsdf : 1.0f;
}

void TsdfVolume::integrate(const uint16_t* depthMm, int w, int h,
                            float fx, float fy, float cx, float cy,
                            const float* c2w)
{
    ++frameCount_;

    // Camera position and rotation from column-major c2w.
    float camPosX = c2w[12], camPosY = c2w[13], camPosZ = c2w[14];
    if (!std::isfinite(camPosX) || !std::isfinite(camPosY) || !std::isfinite(camPosZ)) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "integrate #%d: non-finite camera position, skipping frame", frameCount_);
        return;
    }
    // Camera-to-world rotation: world = R * cam + t.
    // R columns: c2w[0..2], c2w[4..6], c2w[8..10]
    float r00=c2w[0],  r01=c2w[1],  r02=c2w[2];
    float r10=c2w[4],  r11=c2w[5],  r12=c2w[6];
    float r20=c2w[8],  r21=c2w[9],  r22=c2w[10];
    if (!std::isfinite(r00) || !std::isfinite(r11) || !std::isfinite(r22)) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "integrate #%d: non-finite rotation matrix, skipping frame", frameCount_);
        return;
    }

    const float stepSize = voxelSize_;  // 1 voxel per step (was 0.5)
    int updatedVoxels = 0;

    for (int iv = 0; iv < h; ++iv) {
        for (int iu = 0; iu < w; ++iu) {
            uint16_t rawMm = depthMm[iv * w + iu];
            if (rawMm == 0) continue;

            float depthM = rawMm * 0.001f;
            if (depthM < 0.3f || depthM > 8.0f) continue;

            // Ray direction in camera space.
            // ARCore: Y+ up; depth image: V+ down → negate (iv-cy)/fy.
            float rayCamX = (iu - cx) / fx;
            float rayCamY = -(iv - cy) / fy;
            // rayCamZ = 1.0 (depth is measured along camera Z axis)

            // March through the truncation band along camera Z.
            float tNear = std::max(0.1f, depthM - truncation_);
            float tFar  = depthM + truncation_;

            for (float t = tNear; t < tFar + stepSize; t += stepSize) {
                float sdf = depthM - t;
                if (sdf < -truncation_) break;  // ray exits truncation band
                float tsdfVal = std::min(sdf / truncation_, 1.0f);

                // Sample point in camera space.
                // ARCore OpenGL convention: camera looks down -Z, so depth along -Z.
                float ptX =  rayCamX * t;
                float ptY =  rayCamY * t;
                float ptZ = -t;

                // Transform to world space
                float wx = r00*ptX + r10*ptY + r20*ptZ + camPosX;
                float wy = r01*ptX + r11*ptY + r21*ptZ + camPosY;
                float wz = r02*ptX + r12*ptY + r22*ptZ + camPosZ;

                // Global voxel indices (floor mandatory for negative coords)
                int vgx = static_cast<int>(std::floor(wx / voxelSize_));
                int vgy = static_cast<int>(std::floor(wy / voxelSize_));
                int vgz = static_cast<int>(std::floor(wz / voxelSize_));

                int bx = toBlock(vgx), by = toBlock(vgy), bz = toBlock(vgz);
                int lx = vgx - bx * BLOCK_SIZE;
                int ly = vgy - by * BLOCK_SIZE;
                int lz = vgz - bz * BLOCK_SIZE;

                BlockKey bk{bx, by, bz};
                TsdfBlock* block = getOrCreate(bk);
                if (!block) continue;
                block->lastTouchedFrame = frameCount_;
                block->dirty = true;

                auto& voxel = block->at(lx, ly, lz);
                float wNew = voxel.weight + 1.0f;
                voxel.tsdf   = (voxel.weight * voxel.tsdf + tsdfVal) / wNew;
                voxel.weight = std::min(wNew, 100.0f);

                // Neighboring blocks sharing this boundary voxel need re-marching
                if (lx == 0) markDirtyNeighbor(bx - 1, by, bz);
                if (ly == 0) markDirtyNeighbor(bx, by - 1, bz);
                if (lz == 0) markDirtyNeighbor(bx, by, bz - 1);

                ++updatedVoxels;
            }
        }
    }

    if (frameCount_ % 60 == 0) {
        evictDistantBlocks(camPosX, camPosY, camPosZ);
    }

    if (frameCount_ == 1 || frameCount_ % 30 == 0) {
        __android_log_print(ANDROID_LOG_DEBUG, TAG,
            "integrate #%d: %d voxel-steps, %zu blocks (depth %dx%d fx=%.1f)",
            frameCount_, updatedVoxels, blocks_.size(), w, h, fx);
    }
}

void TsdfVolume::extractMesh(MeshBuffers& out) {
    out.vertices.clear();
    out.normals.clear();
    out.vertexCount = 0;

    int dirtyCount = 0;
    for (auto& [key, block] : blocks_) {
        if (!block->dirty) continue;
        ++dirtyCount;
        MeshBuffers blockMesh;
        runMarchingCubesBlock(*this, key, voxelSize_, blockMesh);
        BlockMesh& cache = meshCache_[key];
        cache.vertices   = std::move(blockMesh.vertices);
        cache.normals    = std::move(blockMesh.normals);
        cache.vertexCount = blockMesh.vertexCount;
        block->dirty = false;
    }

    for (auto& [key, bm] : meshCache_) {
        if (bm.vertexCount == 0) continue;
        out.vertices.insert(out.vertices.end(), bm.vertices.begin(), bm.vertices.end());
        out.normals.insert(out.normals.end(),   bm.normals.begin(),  bm.normals.end());
        out.vertexCount += bm.vertexCount;
    }

    __android_log_print(ANDROID_LOG_DEBUG, TAG,
        "extractMesh: %d verts from %zu blocks (%d re-marched)",
        out.vertexCount, blocks_.size(), dirtyCount);
}

void TsdfVolume::evictDistantBlocks(float camX, float camY, float camZ) {
    const float  evictRadius2 = 12.0f * 12.0f;
    const int    evictAge     = 300;
    const size_t maxBlocks    = 20000;  // ~80 MB TSDF; 3cm voxels cover room in ~2k blocks

    auto it = blocks_.begin();
    while (it != blocks_.end()) {
        const BlockKey& k = it->first;
        float bwx = (k.x * BLOCK_SIZE + BLOCK_SIZE * 0.5f) * voxelSize_;
        float bwy = (k.y * BLOCK_SIZE + BLOCK_SIZE * 0.5f) * voxelSize_;
        float bwz = (k.z * BLOCK_SIZE + BLOCK_SIZE * 0.5f) * voxelSize_;
        float dx = bwx - camX, dy = bwy - camY, dz = bwz - camZ;
        float dist2 = dx*dx + dy*dy + dz*dz;
        int   age   = frameCount_ - it->second->lastTouchedFrame;

        bool farAndOld   = (dist2 > evictRadius2) && (age > evictAge);
        bool overBudget  = (blocks_.size() > maxBlocks) && (age > 120);

        if (farAndOld || overBudget) {
            meshCache_.erase(k);
            delete it->second;
            it = blocks_.erase(it);
        } else {
            ++it;
        }
    }
}
