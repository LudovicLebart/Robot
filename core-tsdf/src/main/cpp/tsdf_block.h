#pragma once

static constexpr int BLOCK_SIZE   = 8;
static constexpr int BLOCK_VOXELS = BLOCK_SIZE * BLOCK_SIZE * BLOCK_SIZE;  // 512

struct TsdfVoxel {
    float tsdf   = 1.0f;
    float weight = 0.0f;
};

struct TsdfBlock {
    TsdfVoxel voxels[BLOCK_VOXELS];
    bool dirty            = true;
    int  lastTouchedFrame = 0;

    TsdfVoxel& at(int lx, int ly, int lz) noexcept {
        return voxels[lx + BLOCK_SIZE * (ly + BLOCK_SIZE * lz)];
    }
    const TsdfVoxel& at(int lx, int ly, int lz) const noexcept {
        return voxels[lx + BLOCK_SIZE * (ly + BLOCK_SIZE * lz)];
    }
};
