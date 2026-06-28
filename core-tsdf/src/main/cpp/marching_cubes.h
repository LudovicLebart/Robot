#pragma once
#include "tsdf_volume.h"
#include "block_hash.h"

/** Runs Marching Cubes on one 8×8×8 block. Cross-block corners are fetched via vol.tsdfAt(). */
void runMarchingCubesBlock(const TsdfVolume& vol, const BlockKey& bk,
                            float voxelSize, MeshBuffers& out);
