#pragma once
#include <vector>
#include <unordered_map>
#include <cstdint>
#include "block_hash.h"
#include "tsdf_block.h"

struct MeshBuffers {
    std::vector<float> vertices;  // x,y,z per vertex
    std::vector<float> normals;   // nx,ny,nz per vertex
    int vertexCount = 0;
};

class TsdfVolume {
public:
    // sx/sy/sz are ignored — voxel hashing grows unboundedly.
    TsdfVolume(int sx, int sy, int sz, float voxelSize, float truncation);
    ~TsdfVolume();

    /**
     * Integrate one depth frame (pixel-driven ray marching).
     * @param depthMm   DEPTH16 values: bits[15:3]=mm, bits[2:0]=confidence.
     *                  Caller must zero out low-confidence pixels before passing.
     * @param c2w       Column-major 4×4 camera-to-world transform.
     */
    void integrate(const uint16_t* depthMm, int w, int h,
                   float fx, float fy, float cx, float cy,
                   const float* c2w);

    /** Re-march dirty blocks and aggregate all cached block meshes. */
    void extractMesh(MeshBuffers& out);

    void reset();

    /** TSDF value at global voxel (gx,gy,gz). Returns 1.0 if absent or under-observed. */
    float tsdfAt(int gx, int gy, int gz) const;

    float voxelSize() const { return voxelSize_; }

private:
    float voxelSize_;
    float truncation_;
    int   frameCount_ = 0;

    std::unordered_map<BlockKey, TsdfBlock*, BlockKeyHash> blocks_;

    struct BlockMesh {
        std::vector<float> vertices;
        std::vector<float> normals;
        int vertexCount = 0;
    };
    std::unordered_map<BlockKey, BlockMesh, BlockKeyHash> meshCache_;

    TsdfBlock* getOrCreate(const BlockKey& key);
    void markDirtyNeighbor(int bx, int by, int bz);
    void evictDistantBlocks(float camX, float camY, float camZ);

    // Floor-based block coordinate: handles negative voxel indices correctly.
    static int toBlock(int g) noexcept {
        return (g >= 0) ? (g / BLOCK_SIZE)
                        : ((g - BLOCK_SIZE + 1) / BLOCK_SIZE);
    }
};
