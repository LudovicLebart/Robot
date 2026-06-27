#pragma once
#include <vector>
#include <cstdint>

struct TsdfVoxel {
    float tsdf   = 1.0f;
    float weight = 0.0f;
};

struct MeshBuffers {
    std::vector<float> vertices;  // x,y,z per vertex
    std::vector<float> normals;   // nx,ny,nz per vertex
    int vertexCount = 0;
};

class TsdfVolume {
public:
    TsdfVolume(int sx, int sy, int sz, float voxelSize, float truncation);
    ~TsdfVolume() = default;

    /**
     * Integrate a depth image into the volume.
     * @param depthMm   raw depth values in millimetres (DEPTH16 from ARCore, uint16_t, 0 = invalid)
     * @param w,h       depth image dimensions
     * @param fx,fy,cx,cy  depth camera intrinsics (pixels)
     * @param cameraToWorld  column-major 4x4 transform
     */
    void integrate(const uint16_t* depthMm, int w, int h,
                   float fx, float fy, float cx, float cy,
                   const float* cameraToWorld);

    void extractMesh(MeshBuffers& out);

    void reset();

    // Public for Marching Cubes access
    float interpolate(int x, int y, int z) const;
    int sizeX() const { return sizeX_; }
    int sizeY() const { return sizeY_; }
    int sizeZ() const { return sizeZ_; }
    float voxelSize() const { return voxelSize_; }
    float originX() const { return originX_; }
    float originY() const { return originY_; }
    float originZ() const { return originZ_; }

private:
    int sizeX_, sizeY_, sizeZ_;
    float voxelSize_;
    float truncation_;
    std::vector<TsdfVoxel> voxels_;

    // size_t arithmetic prevents int overflow for large grids (e.g. 300*150*300 > INT_MAX/4)
    size_t idx(int x, int y, int z) const {
        return static_cast<size_t>(x)
             + static_cast<size_t>(sizeX_) * (static_cast<size_t>(y)
             + static_cast<size_t>(sizeY_) * static_cast<size_t>(z));
    }

    float originX_ = -3.0f;
    float originY_ = -1.5f;
    float originZ_ = -3.0f;
};
