#include <jni.h>
#include <android/log.h>
#include "tsdf_volume.h"
#include "marching_cubes.h"

#define TAG "TsdfJNI"

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_robot_tsdf_TsdfVolume_nativeCreate(
    JNIEnv*, jobject, jint sx, jint sy, jint sz, jfloat voxelSize, jfloat truncation)
{
    auto* vol = new TsdfVolume(sx, sy, sz, voxelSize, truncation);
    return reinterpret_cast<jlong>(vol);
}

JNIEXPORT void JNICALL
Java_com_robot_tsdf_TsdfVolume_nativeIntegrate(
    JNIEnv* env, jobject,
    jlong handle,
    jshortArray depthArr, jint w, jint h,
    jfloat fx, jfloat fy, jfloat cx, jfloat cy,
    jfloatArray c2wArr)
{
    auto* vol = reinterpret_cast<TsdfVolume*>(handle);
    jshort* depth = env->GetShortArrayElements(depthArr, nullptr);
    jfloat* c2w   = env->GetFloatArrayElements(c2wArr, nullptr);

    vol->integrate(reinterpret_cast<const int16_t*>(depth), w, h,
                   fx, fy, cx, cy, c2w);

    env->ReleaseShortArrayElements(depthArr, depth, JNI_ABORT);
    env->ReleaseFloatArrayElements(c2wArr, c2w, JNI_ABORT);
}

JNIEXPORT jobject JNICALL
Java_com_robot_tsdf_TsdfVolume_nativeExtractMesh(JNIEnv* env, jobject, jlong handle)
{
    auto* vol = reinterpret_cast<TsdfVolume*>(handle);
    MeshBuffers mesh;
    vol->extractMesh(mesh);  // calls runMarchingCubes internally

    if (mesh.vertexCount == 0) return nullptr;

    // Return a float[] interleaved [vx,vy,vz, nx,ny,nz, ...] per vertex
    int totalFloats = mesh.vertexCount * 6;
    jfloatArray result = env->NewFloatArray(totalFloats);
    if (!result) return nullptr;

    std::vector<float> interleaved;
    interleaved.reserve(totalFloats);
    for (int i = 0; i < mesh.vertexCount; ++i) {
        interleaved.push_back(mesh.vertices[i*3+0]);
        interleaved.push_back(mesh.vertices[i*3+1]);
        interleaved.push_back(mesh.vertices[i*3+2]);
        interleaved.push_back(mesh.normals[i*3+0]);
        interleaved.push_back(mesh.normals[i*3+1]);
        interleaved.push_back(mesh.normals[i*3+2]);
    }
    env->SetFloatArrayRegion(result, 0, totalFloats, interleaved.data());
    return result;
}

JNIEXPORT void JNICALL
Java_com_robot_tsdf_TsdfVolume_nativeReset(JNIEnv*, jobject, jlong handle)
{
    reinterpret_cast<TsdfVolume*>(handle)->reset();
}

JNIEXPORT void JNICALL
Java_com_robot_tsdf_TsdfVolume_nativeDestroy(JNIEnv*, jobject, jlong handle)
{
    delete reinterpret_cast<TsdfVolume*>(handle);
}

} // extern "C"
