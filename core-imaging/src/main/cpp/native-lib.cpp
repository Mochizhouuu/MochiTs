#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cmath>
#include <algorithm>
#include <cstring>

#ifdef HAVE_OPENCV
#include <opencv2/opencv.hpp>
#include <opencv2/photo.hpp>
#include <opencv2/imgproc.hpp>
#endif

#define LOG_TAG "NativeImaging"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct PointF {
    float x;
    float y;
};

// Mask Selection helper functions operating on ALPHA_8 native bitmap buffer
static void drawCircleAlpha8(uint8_t* pixels, int width, int height, float cx, float cy, float radius, bool draw) {
    int minX = std::max(0, (int)std::floor(cx - radius));
    int maxX = std::min(width - 1, (int)std::ceil(cx + radius));
    int minY = std::max(0, (int)std::floor(cy - radius));
    int maxY = std::min(height - 1, (int)std::ceil(cy + radius));

    float r2 = radius * radius;
    uint8_t val = draw ? 255 : 0;

    for (int y = minY; y <= maxY; ++y) {
        float dy = (float)y - cy;
        float dy2 = dy * dy;
        int rowIdx = y * width;
        for (int x = minX; x <= maxX; ++x) {
            float dx = (float)x - cx;
            if (dx * dx + dy2 <= r2) {
                pixels[rowIdx + x] = val;
            }
        }
    }
}

static void drawLineAlpha8(uint8_t* pixels, int width, int height, float x0, float y0, float x1, float y1, float radius, bool draw) {
    float dx = x1 - x0;
    float dy = y1 - y0;
    float len = std::sqrt(dx * dx + dy * dy);
    if (len == 0.0f) {
        drawCircleAlpha8(pixels, width, height, x0, y0, radius, draw);
        return;
    }

    int steps = (int)std::ceil(len);
    float stepX = dx / steps;
    float stepY = dy / steps;

    float curX = x0;
    float curY = y0;
    for (int i = 0; i <= steps; ++i) {
        drawCircleAlpha8(pixels, width, height, curX, curY, radius, draw);
        curX += stepX;
        curY += stepY;
    }
}

static void fillPolygonAlpha8(uint8_t* pixels, int width, int height, const std::vector<PointF>& pts, uint8_t val) {
    if (pts.size() < 3) return;

    int minY = height - 1;
    int maxY = 0;
    for (const auto& p : pts) {
        minY = std::min(minY, std::max(0, (int)std::floor(p.y)));
        maxY = std::max(maxY, std::min(height - 1, (int)std::ceil(p.y)));
    }

    int numPts = (int)pts.size();

    for (int y = minY; y <= maxY; ++y) {
        float scanY = (float)y + 0.5f;
        std::vector<float> nodeX;

        int j = numPts - 1;
        for (int i = 0; i < numPts; ++i) {
            if ((pts[i].y < scanY && pts[j].y >= scanY) || (pts[j].y < scanY && pts[i].y >= scanY)) {
                float ix = pts[i].x + (scanY - pts[i].y) / (pts[j].y - pts[i].y) * (pts[j].x - pts[i].x);
                nodeX.push_back(ix);
            }
            j = i;
        }

        std::sort(nodeX.begin(), nodeX.end());

        int rowIdx = y * width;
        for (size_t k = 0; k < nodeX.size(); k += 2) {
            if (k + 1 >= nodeX.size()) break;
            int startX = std::max(0, (int)std::ceil(nodeX[k]));
            int endX = std::min(width - 1, (int)std::floor(nodeX[k + 1]));
            for (int x = startX; x <= endX; ++x) {
                pixels[rowIdx + x] = val;
            }
        }
    }
}

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_mochits_core_imaging_NativeBridge_nativeGetOpenCVVersion(
        JNIEnv* env,
        jobject /* this */) {
#ifdef HAVE_OPENCV
    std::string version = CV_VERSION;
    return env->NewStringUTF(version.c_str());
#else
    return env->NewStringUTF("OpenCV Not Loaded");
#endif
}

JNIEXPORT void JNICALL
Java_com_mochits_core_imaging_NativeBridge_nativeDrawCircle(
        JNIEnv* env,
        jobject /* this */,
        jobject bitmap,
        jfloat cx,
        jfloat cy,
        jfloat radius,
        jboolean draw) {
    AndroidBitmapInfo info;
    void* pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0 || info.format != ANDROID_BITMAP_FORMAT_A_8) return;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0 || !pixels) return;

    drawCircleAlpha8(static_cast<uint8_t*>(pixels), info.width, info.height, cx, cy, radius, draw);

    AndroidBitmap_unlockPixels(env, bitmap);
}

JNIEXPORT void JNICALL
Java_com_mochits_core_imaging_NativeBridge_nativeDrawLine(
        JNIEnv* env,
        jobject /* this */,
        jobject bitmap,
        jfloat x0,
        jfloat y0,
        jfloat x1,
        jfloat y1,
        jfloat radius,
        jboolean draw) {
    AndroidBitmapInfo info;
    void* pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0 || info.format != ANDROID_BITMAP_FORMAT_A_8) return;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0 || !pixels) return;

    drawLineAlpha8(static_cast<uint8_t*>(pixels), info.width, info.height, x0, y0, x1, y1, radius, draw);

    AndroidBitmap_unlockPixels(env, bitmap);
}

JNIEXPORT void JNICALL
Java_com_mochits_core_imaging_NativeBridge_nativeDrawPolygon(
        JNIEnv* env,
        jobject /* this */,
        jobject bitmap,
        jfloatArray pointsX,
        jfloatArray pointsY,
        jboolean draw) {
    if (!pointsX || !pointsY) return;
    jsize lenX = env->GetArrayLength(pointsX);
    jsize lenY = env->GetArrayLength(pointsY);
    if (lenX != lenY || lenX < 3) return;

    jfloat* arrX = env->GetFloatArrayElements(pointsX, nullptr);
    jfloat* arrY = env->GetFloatArrayElements(pointsY, nullptr);
    if (!arrX || !arrY) return;

    std::vector<PointF> pts(lenX);
    for (jsize i = 0; i < lenX; ++i) {
        pts[i] = {arrX[i], arrY[i]};
    }

    env->ReleaseFloatArrayElements(pointsX, arrX, JNI_ABORT);
    env->ReleaseFloatArrayElements(pointsY, arrY, JNI_ABORT);

    AndroidBitmapInfo info;
    void* pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0 || info.format != ANDROID_BITMAP_FORMAT_A_8) return;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0 || !pixels) return;

    fillPolygonAlpha8(static_cast<uint8_t*>(pixels), info.width, info.height, pts, draw ? 255 : 0);

    AndroidBitmap_unlockPixels(env, bitmap);
}

JNIEXPORT void JNICALL
Java_com_mochits_core_imaging_NativeBridge_nativeDrawRect(
        JNIEnv* env,
        jobject /* this */,
        jobject bitmap,
        jfloat left,
        jfloat top,
        jfloat right,
        jfloat bottom,
        jboolean draw) {
    AndroidBitmapInfo info;
    void* pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0 || info.format != ANDROID_BITMAP_FORMAT_A_8) return;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0 || !pixels) return;

    int width = info.width;
    int height = info.height;
    int minX = std::max(0, (int)std::floor(std::min(left, right)));
    int maxX = std::min(width - 1, (int)std::ceil(std::max(left, right)));
    int minY = std::max(0, (int)std::floor(std::min(top, bottom)));
    int maxY = std::min(height - 1, (int)std::ceil(std::max(top, bottom)));

    uint8_t val = draw ? 255 : 0;
    uint8_t* ptr = static_cast<uint8_t*>(pixels);

    for (int y = minY; y <= maxY; ++y) {
        int rowIdx = y * width;
        for (int x = minX; x <= maxX; ++x) {
            ptr[rowIdx + x] = val;
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
}

JNIEXPORT void JNICALL
Java_com_mochits_core_imaging_NativeBridge_nativeClearMask(
        JNIEnv* env,
        jobject /* this */,
        jobject bitmap) {
    AndroidBitmapInfo info;
    void* pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0 || info.format != ANDROID_BITMAP_FORMAT_A_8) return;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0 || !pixels) return;

    std::memset(pixels, 0, info.width * info.height);

    AndroidBitmap_unlockPixels(env, bitmap);
}

JNIEXPORT void JNICALL
Java_com_mochits_core_imaging_NativeBridge_nativeInvertMask(
        JNIEnv* env,
        jobject /* this */,
        jobject bitmap) {
    AndroidBitmapInfo info;
    void* pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0 || info.format != ANDROID_BITMAP_FORMAT_A_8) return;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0 || !pixels) return;

    uint8_t* ptr = static_cast<uint8_t*>(pixels);
    int total = info.width * info.height;
    for (int i = 0; i < total; ++i) {
        ptr[i] = 255 - ptr[i];
    }

    AndroidBitmap_unlockPixels(env, bitmap);
}

JNIEXPORT jboolean JNICALL
Java_com_mochits_core_imaging_NativeBridge_nativeHasMask(
        JNIEnv* env,
        jobject /* this */,
        jobject bitmap) {
    AndroidBitmapInfo info;
    void* pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0 || info.format != ANDROID_BITMAP_FORMAT_A_8) return JNI_FALSE;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0 || !pixels) return JNI_FALSE;

    uint8_t* ptr = static_cast<uint8_t*>(pixels);
    int total = info.width * info.height;
    bool has = false;

    for (int i = 0; i < total; i += 16) {
        if (ptr[i] > 0) {
            has = true;
            break;
        }
    }
    if (!has) {
        for (int i = 0; i < total; ++i) {
            if (ptr[i] > 0) {
                has = true;
                break;
            }
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return has ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_mochits_core_imaging_NativeBridge_nativeInpaintTelea(
        JNIEnv* env,
        jobject /* this */,
        jobject srcBitmap,
        jobject maskBitmap,
        jobject dstBitmap,
        jfloat radius) {
    AndroidBitmapInfo srcInfo, maskInfo, dstInfo;
    void* srcPixels = nullptr;
    void* maskPixels = nullptr;
    void* dstPixels = nullptr;

    if (AndroidBitmap_getInfo(env, srcBitmap, &srcInfo) < 0 || srcInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    if (AndroidBitmap_getInfo(env, maskBitmap, &maskInfo) < 0 || maskInfo.format != ANDROID_BITMAP_FORMAT_A_8) return JNI_FALSE;
    if (AndroidBitmap_getInfo(env, dstBitmap, &dstInfo) < 0 || dstInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;

    if (srcInfo.width != dstInfo.width || srcInfo.height != dstInfo.height) return JNI_FALSE;
    if (srcInfo.width != maskInfo.width || srcInfo.height != maskInfo.height) return JNI_FALSE;

    if (AndroidBitmap_lockPixels(env, srcBitmap, &srcPixels) < 0 || !srcPixels) return JNI_FALSE;
    if (AndroidBitmap_lockPixels(env, maskBitmap, &maskPixels) < 0 || !maskPixels) {
        AndroidBitmap_unlockPixels(env, srcBitmap);
        return JNI_FALSE;
    }
    if (AndroidBitmap_lockPixels(env, dstBitmap, &dstPixels) < 0 || !dstPixels) {
        AndroidBitmap_unlockPixels(env, maskBitmap);
        AndroidBitmap_unlockPixels(env, srcBitmap);
        return JNI_FALSE;
    }

#ifdef HAVE_OPENCV
    int w = srcInfo.width;
    int h = srcInfo.height;

    cv::Mat srcMat(h, w, CV_8UC4, srcPixels);
    cv::Mat bgrMat;
    cv::cvtColor(srcMat, bgrMat, cv::COLOR_RGBA2BGR);

    cv::Mat maskMat(h, w, CV_8UC1, maskPixels);

    cv::Mat inpaintedBgr;
    cv::inpaint(bgrMat, maskMat, inpaintedBgr, static_cast<double>(radius), cv::INPAINT_TELEA);

    cv::Mat dstMat(h, w, CV_8UC4, dstPixels);
    cv::cvtColor(inpaintedBgr, dstMat, cv::COLOR_BGR2RGBA);

    AndroidBitmap_unlockPixels(env, dstBitmap);
    AndroidBitmap_unlockPixels(env, maskBitmap);
    AndroidBitmap_unlockPixels(env, srcBitmap);
    return JNI_TRUE;
#else
    // Fallback if compiled without OpenCV (e.g. initial test phase before OpenCV CMake linking)
    std::memcpy(dstPixels, srcPixels, srcInfo.height * srcInfo.stride);
    AndroidBitmap_unlockPixels(env, dstBitmap);
    AndroidBitmap_unlockPixels(env, maskBitmap);
    AndroidBitmap_unlockPixels(env, srcBitmap);
    return JNI_TRUE;
#endif
}

}
