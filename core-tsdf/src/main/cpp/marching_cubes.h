#pragma once
#include "tsdf_volume.h"

// Runs Marching Cubes on [TsdfVolume] and appends results into [MeshBuffers].
void runMarchingCubes(const TsdfVolume& vol, int sizeX, int sizeY, int sizeZ,
                      float voxelSize, float originX, float originY, float originZ,
                      MeshBuffers& out);
