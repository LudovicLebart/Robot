#include "tsdf_volume.h"
#include "marching_cubes.h"
#include <cmath>
#include <algorithm>
#include <android/log.h>

#define TAG "TsdfVolume"

TsdfVolume::TsdfVolume(int sx, int sy, int sz, float voxelSize, float truncation)
    : sizeX_(sx), sizeY_(sy), sizeZ_(sz),
      voxelSize_(voxelSize), truncation_(truncation),
      voxels_(static_cast<size_t>(sx) * static_cast<size_t>(sy) * static_cast<size_t>(sz))
{}

void TsdfVolume::reset() {
    std::fill(voxels_.begin(), voxels_.end(), TsdfVoxel{});
}

void TsdfVolume::integrate(const uint16_t* depthMm, int w, int h,
                            float fx, float fy, float cx, float cy,
                            const float* c2w)
{
    const float newWeight = 1.0f;

    for (int vz = 0; vz < sizeZ_; ++vz) {
        for (int vy = 0; vy < sizeY_; ++vy) {
            for (int vx = 0; vx < sizeX_; ++vx) {
                // World position of voxel centre
                float wx = originX_ + (vx + 0.5f) * voxelSize_;
                float wy = originY_ + (vy + 0.5f) * voxelSize_;
                float wz = originZ_ + (vz + 0.5f) * voxelSize_;

                // Transform world → camera (inverse of c2w = world-to-cam)
                // c2w is col-major: c2w[col*4+row]
                // world-to-cam = inverse(c2w). For rigid body: R^T, -R^T*t
                float r00=c2w[0], r10=c2w[1], r20=c2w[2];
                float r01=c2w[4], r11=c2w[5], r21=c2w[6];
                float r02=c2w[8], r12=c2w[9], r22=c2w[10];
                float tx =c2w[12], ty=c2w[13], tz=c2w[14];

                float dx = wx - tx, dy = wy - ty, dz = wz - tz;
                float camX = r00*dx + r10*dy + r20*dz;
                float camY = r01*dx + r11*dy + r21*dz;
                float camZ = r02*dx + r12*dy + r22*dz;

                if (camZ <= 0.0f) continue;

                // Project to depth image
                float u = fx * camX / camZ + cx;
                float v = fy * camY / camZ + cy;

                int iu = static_cast<int>(u + 0.5f);
                int iv = static_cast<int>(v + 0.5f);
                if (iu < 0 || iu >= w || iv < 0 || iv >= h) continue;

                uint16_t rawMm = depthMm[iv * w + iu];
                if (rawMm == 0) continue;  // 0 means no depth data
                float measuredZ = rawMm * 0.001f;  // mm → m

                float sdf = measuredZ - camZ;
                if (sdf < -truncation_) continue;
                float tsdfVal = std::min(sdf / truncation_, 1.0f);

                auto& voxel = voxels_[idx(vx, vy, vz)];
                float w_new = voxel.weight + newWeight;
                voxel.tsdf   = (voxel.weight * voxel.tsdf + newWeight * tsdfVal) / w_new;
                voxel.weight = std::min(w_new, 100.0f);
            }
        }
    }
}

float TsdfVolume::interpolate(int x, int y, int z) const {
    if (x < 0 || x >= sizeX_ || y < 0 || y >= sizeY_ || z < 0 || z >= sizeZ_)
        return 1.0f;
    return voxels_[idx(x,y,z)].tsdf;
}

void TsdfVolume::extractMesh(MeshBuffers& out) {
    runMarchingCubes(*this, sizeX_, sizeY_, sizeZ_,
                     voxelSize_, originX_, originY_, originZ_, out);
}
