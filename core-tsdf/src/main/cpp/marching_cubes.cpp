#include "marching_cubes.h"
#include <cmath>

static const int edgeTable[256] = {
#include "mc_edge_table.inl"
};
static const int triTable[256][16] = {
#include "mc_tri_table.inl"
};

static void vertexInterp(float iso,
                         float x1, float y1, float z1, float v1,
                         float x2, float y2, float z2, float v2,
                         float& ox, float& oy, float& oz) {
    float t = std::abs(v2 - v1) < 1e-6f ? 0.5f : (iso - v1) / (v2 - v1);
    ox = x1 + t * (x2 - x1);
    oy = y1 + t * (y2 - y1);
    oz = z1 + t * (z2 - z1);
}

void runMarchingCubesBlock(const TsdfVolume& vol, const BlockKey& bk,
                            float vs, MeshBuffers& out) {
    out.vertices.clear();
    out.normals.clear();
    out.vertexCount = 0;

    const float iso = 0.0f;

    for (int lz = 0; lz < BLOCK_SIZE; ++lz)
    for (int ly = 0; ly < BLOCK_SIZE; ++ly)
    for (int lx = 0; lx < BLOCK_SIZE; ++lx) {
        // Global voxel at this cube's minimum corner
        int gx = bk.x * BLOCK_SIZE + lx;
        int gy = bk.y * BLOCK_SIZE + ly;
        int gz = bk.z * BLOCK_SIZE + lz;

        // World-space position of the cube's minimum corner
        float cx = gx * vs, cy = gy * vs, cz = gz * vs;

        // 8 corners of the Marching Cubes cell (tsdfAt crosses block boundaries)
        float v[8];
        v[0] = vol.tsdfAt(gx,   gy,   gz  );
        v[1] = vol.tsdfAt(gx+1, gy,   gz  );
        v[2] = vol.tsdfAt(gx+1, gy+1, gz  );
        v[3] = vol.tsdfAt(gx,   gy+1, gz  );
        v[4] = vol.tsdfAt(gx,   gy,   gz+1);
        v[5] = vol.tsdfAt(gx+1, gy,   gz+1);
        v[6] = vol.tsdfAt(gx+1, gy+1, gz+1);
        v[7] = vol.tsdfAt(gx,   gy+1, gz+1);

        // Corner world positions
        float px[8] = { cx,    cx+vs, cx+vs, cx,    cx,    cx+vs, cx+vs, cx    };
        float py[8] = { cy,    cy,    cy+vs, cy+vs, cy,    cy,    cy+vs, cy+vs };
        float pz[8] = { cz,    cz,    cz,    cz,    cz+vs, cz+vs, cz+vs, cz+vs };

        // Skip cells with any unobserved corner (sentinel = 2.0f).
        // Without this, observed-to-unobserved boundaries produce false zero-crossings.
        bool anyUnobserved = false;
        for (int i = 0; i < 8; ++i) if (v[i] > 1.5f) { anyUnobserved = true; break; }
        if (anyUnobserved) continue;

        int cubeIdx = 0;
        for (int i = 0; i < 8; ++i) if (v[i] < iso) cubeIdx |= (1 << i);
        if (edgeTable[cubeIdx] == 0) continue;

        float ep[12][3];
        auto interp = [&](int a, int b, int e) {
            vertexInterp(iso,
                         px[a], py[a], pz[a], v[a],
                         px[b], py[b], pz[b], v[b],
                         ep[e][0], ep[e][1], ep[e][2]);
        };
        if (edgeTable[cubeIdx] & 1)    interp(0, 1, 0);
        if (edgeTable[cubeIdx] & 2)    interp(1, 2, 1);
        if (edgeTable[cubeIdx] & 4)    interp(2, 3, 2);
        if (edgeTable[cubeIdx] & 8)    interp(3, 0, 3);
        if (edgeTable[cubeIdx] & 16)   interp(4, 5, 4);
        if (edgeTable[cubeIdx] & 32)   interp(5, 6, 5);
        if (edgeTable[cubeIdx] & 64)   interp(6, 7, 6);
        if (edgeTable[cubeIdx] & 128)  interp(7, 4, 7);
        if (edgeTable[cubeIdx] & 256)  interp(0, 4, 8);
        if (edgeTable[cubeIdx] & 512)  interp(1, 5, 9);
        if (edgeTable[cubeIdx] & 1024) interp(2, 6, 10);
        if (edgeTable[cubeIdx] & 2048) interp(3, 7, 11);

        for (int i = 0; triTable[cubeIdx][i] != -1; i += 3) {
            auto& a = ep[triTable[cubeIdx][i]];
            auto& b = ep[triTable[cubeIdx][i + 1]];
            auto& c = ep[triTable[cubeIdx][i + 2]];

            // Face normal via cross product
            float ux = b[0]-a[0], uy = b[1]-a[1], uz = b[2]-a[2];
            float wx = c[0]-a[0], wy = c[1]-a[1], wz = c[2]-a[2];
            float nx = uy*wz - uz*wy;
            float ny = uz*wx - ux*wz;
            float nz = ux*wy - uy*wx;
            float len = std::sqrt(nx*nx + ny*ny + nz*nz);
            if (len < 1e-8f) continue;
            nx /= len; ny /= len; nz /= len;

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
