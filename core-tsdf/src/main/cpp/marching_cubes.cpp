#include "marching_cubes.h"
#include <array>
#include <cmath>

// Standard Marching Cubes edge/triangle tables
static const int edgeTable[256] = {
#include "mc_edge_table.inl"
};
static const int triTable[256][16] = {
#include "mc_tri_table.inl"
};

static void vertexInterp(float iso,
                         float x1,float y1,float z1,float v1,
                         float x2,float y2,float z2,float v2,
                         float& ox,float& oy,float& oz) {
    float t = std::abs(v2 - v1) < 1e-6f ? 0.5f : (iso - v1) / (v2 - v1);
    ox = x1 + t*(x2-x1);
    oy = y1 + t*(y2-y1);
    oz = z1 + t*(z2-z1);
}

void runMarchingCubes(const TsdfVolume& vol, int sX, int sY, int sZ,
                      float vs, float ox, float oy, float oz,
                      MeshBuffers& out) {
    out.vertices.clear();
    out.normals.clear();

    const float iso = 0.0f;

    for (int z = 0; z < sZ - 1; ++z)
    for (int y = 0; y < sY - 1; ++y)
    for (int x = 0; x < sX - 1; ++x) {
        // Cube corners in world space
        float cx = ox + x * vs, cy = oy + y * vs, cz = oz + z * vs;

        float v[8];
        // vol.interpolate is const — access via friend or expose per-voxel tsdf
        // We call vol.interpolate(x+dx, y+dy, z+dz)
        // (method exposed as public in tsdf_volume.h)
        v[0] = vol.interpolate(x,   y,   z  );
        v[1] = vol.interpolate(x+1, y,   z  );
        v[2] = vol.interpolate(x+1, y+1, z  );
        v[3] = vol.interpolate(x,   y+1, z  );
        v[4] = vol.interpolate(x,   y,   z+1);
        v[5] = vol.interpolate(x+1, y,   z+1);
        v[6] = vol.interpolate(x+1, y+1, z+1);
        v[7] = vol.interpolate(x,   y+1, z+1);

        // Corner world positions
        float px[8] = {cx,cx+vs,cx+vs,cx, cx,cx+vs,cx+vs,cx};
        float py[8] = {cy,cy,   cy+vs,cy+vs, cy,cy,cy+vs,cy+vs};
        float pz[8] = {cz,cz,   cz,   cz,  cz+vs,cz+vs,cz+vs,cz+vs};

        int cubeIdx = 0;
        for (int i = 0; i < 8; ++i) if (v[i] < iso) cubeIdx |= (1 << i);
        if (edgeTable[cubeIdx] == 0) continue;

        // Interpolated edge vertices
        float ep[12][3];
        auto interp = [&](int a, int b, int e) {
            vertexInterp(iso, px[a],py[a],pz[a],v[a],
                              px[b],py[b],pz[b],v[b],
                              ep[e][0],ep[e][1],ep[e][2]);
        };
        if (edgeTable[cubeIdx] & 1)    interp(0,1,0);
        if (edgeTable[cubeIdx] & 2)    interp(1,2,1);
        if (edgeTable[cubeIdx] & 4)    interp(2,3,2);
        if (edgeTable[cubeIdx] & 8)    interp(3,0,3);
        if (edgeTable[cubeIdx] & 16)   interp(4,5,4);
        if (edgeTable[cubeIdx] & 32)   interp(5,6,5);
        if (edgeTable[cubeIdx] & 64)   interp(6,7,6);
        if (edgeTable[cubeIdx] & 128)  interp(7,4,7);
        if (edgeTable[cubeIdx] & 256)  interp(0,4,8);
        if (edgeTable[cubeIdx] & 512)  interp(1,5,9);
        if (edgeTable[cubeIdx] & 1024) interp(2,6,10);
        if (edgeTable[cubeIdx] & 2048) interp(3,7,11);

        for (int i = 0; triTable[cubeIdx][i] != -1; i += 3) {
            auto& a = ep[triTable[cubeIdx][i]];
            auto& b = ep[triTable[cubeIdx][i+1]];
            auto& c = ep[triTable[cubeIdx][i+2]];

            // Face normal
            float ux=b[0]-a[0], uy=b[1]-a[1], uz=b[2]-a[2];
            float wx=c[0]-a[0], wy=c[1]-a[1], wz=c[2]-a[2];
            float nx=uy*wz-uz*wy, ny=uz*wx-ux*wz, nz=ux*wy-uy*wx;
            float len = std::sqrt(nx*nx+ny*ny+nz*nz);
            if (len < 1e-8f) continue;
            nx/=len; ny/=len; nz/=len;

            for (auto* p : {&a, &b, &c}) {
                out.vertices.push_back((*p)[0]);
                out.vertices.push_back((*p)[1]);
                out.vertices.push_back((*p)[2]);
                out.normals.push_back(nx);
                out.normals.push_back(ny);
                out.normals.push_back(nz);
            }
            out.vertexCount += 3;
        }
    }
}
