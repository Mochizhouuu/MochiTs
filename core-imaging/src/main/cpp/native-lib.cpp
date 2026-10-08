#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cmath>
#include <algorithm>
#include <cstring>
#include <queue>

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

// Mask Selection helper functions operating on ALPHA_8 native bitmap buffer.
// stride = bytes per row (info.stride); never assume stride == width.
// Row base: pixels + y * stride; pixel: base[x] (1 byte per pixel).
static void drawCircleAlpha8(uint8_t* pixels, int width, int height, int stride, float cx, float cy, float radius, bool draw) {
    int minX = std::max(0, (int)std::floor(cx - radius));
    int maxX = std::min(width - 1, (int)std::ceil(cx + radius));
    int minY = std::max(0, (int)std::floor(cy - radius));
    int maxY = std::min(height - 1, (int)std::ceil(cy + radius));

    float r2 = radius * radius;
    uint8_t val = draw ? 255 : 0;

    for (int y = minY; y <= maxY; ++y) {
        float dy = (float)y - cy;
        float dy2 = dy * dy;
        uint8_t* row = pixels + y * stride;
        for (int x = minX; x <= maxX; ++x) {
            float dx = (float)x - cx;
            if (dx * dx + dy2 <= r2) {
                row[x] = val;
            }
        }
    }
}

static void drawLineAlpha8(uint8_t* pixels, int width, int height, int stride, float x0, float y0, float x1, float y1, float radius, bool draw) {
    float dx = x1 - x0;
    float dy = y1 - y0;
    float len = std::sqrt(dx * dx + dy * dy);
    if (len < 0.001f) {
        drawCircleAlpha8(pixels, width, height, stride, x0, y0, radius, draw);
        return;
    }

    int steps = (int)std::ceil(len / std::max(1.0f, radius * 0.5f));
    for (int i = 0; i <= steps; ++i) {
        float t = (float)i / (float)steps;
        drawCircleAlpha8(pixels, width, height, stride, x0 + t * dx, y0 + t * dy, radius, draw);
    }
}

static bool pointInPolygon(float x, float y, const PointF* pts, int numPts) {
    bool inside = false;
    for (int i = 0, j = numPts - 1; i < numPts; j = i++) {
        if (((pts[i].y > y) != (pts[j].y > y)) &&
            (x < (pts[j].x - pts[i].x) * (y - pts[i].y) / (pts[j].y - pts[i].y) + pts[i].x)) {
            inside = !inside;
        }
    }
    return inside;
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_mochits_core_imaging_NativeBridge_nativeDrawBrushStroke(
        JNIEnv* env,
        jobject /* this */,
        jobject maskBitmap,
        jfloat x0, jfloat y0,
        jfloat x1, jfloat y1,
        jfloat radius,
        jboolean draw) {
    AndroidBitmapInfo info;
    void* pixels = nullptr;

    if (AndroidBitmap_getInfo(env, maskBitmap, &info) < 0) return;
    if (info.format != ANDROID_BITMAP_FORMAT_A_8) return;

    if (AndroidBitmap_lockPixels(env, maskBitmap, &pixels) < 0 || !pixels) return;

    drawLineAlpha8(static_cast<uint8_t*>(pixels), info.width, info.height, info.stride, x0, y0, x1, y1, radius, draw);

    AndroidBitmap_unlockPixels(env, maskBitmap);
}

JNIEXPORT void JNICALL
Java_com_mochits_core_imaging_NativeBridge_nativeApplyLasso(
        JNIEnv* env,
        jobject /* this */,
        jobject maskBitmap,
        jfloatArray pointsArray,
        jboolean draw) {
    AndroidBitmapInfo info;
    void* pixels = nullptr;

    if (AndroidBitmap_getInfo(env, maskBitmap, &info) < 0) return;
    if (info.format != ANDROID_BITMAP_FORMAT_A_8) return;

    jsize len = env->GetArrayLength(pointsArray);
    if (len < 6) return; // Must have at least 3 points (6 floats)

    jfloat* ptsData = env->GetFloatArrayElements(pointsArray, nullptr);
    if (!ptsData) return;

    int numPts = len / 2;
    std::vector<PointF> pts(numPts);
    float minX = (float)info.width, maxX = 0.0f;
    float minY = (float)info.height, maxY = 0.0f;

    for (int i = 0; i < numPts; ++i) {
        pts[i].x = ptsData[i * 2];
        pts[i].y = ptsData[i * 2 + 1];
        minX = std::min(minX, pts[i].x);
        maxX = std::max(maxX, pts[i].x);
        minY = std::min(minY, pts[i].y);
        maxY = std::max(maxY, pts[i].y);
    }
    env->ReleaseFloatArrayElements(pointsArray, ptsData, JNI_ABORT);

    int startX = std::max(0, (int)std::floor(minX));
    int endX = std::min((int)info.width - 1, (int)std::ceil(maxX));
    int startY = std::max(0, (int)std::floor(minY));
    int endY = std::min((int)info.height - 1, (int)std::ceil(maxY));

    if (AndroidBitmap_lockPixels(env, maskBitmap, &pixels) < 0 || !pixels) return;

    uint8_t* maskBytes = static_cast<uint8_t*>(pixels);
    uint8_t val = draw ? 255 : 0;

    for (int y = startY; y <= endY; ++y) {
        uint8_t* row = maskBytes + y * info.stride;
        for (int x = startX; x <= endX; ++x) {
            if (pointInPolygon((float)x + 0.5f, (float)y + 0.5f, pts.data(), numPts)) {
                row[x] = val;
            }
        }
    }

    AndroidBitmap_unlockPixels(env, maskBitmap);
}

// Fallback manual 2D box-dilation (square / Chebyshev distance) on ALPHA_8
static void dilateMaskHand(
        const uint8_t* src, uint8_t* dst,
        int width, int height,
        int srcStride, int dstStride,
        int radius) {
    if (radius <= 0) {
        for (int y = 0; y < height; ++y) {
            std::memcpy(dst + (size_t)y * dstStride, src + (size_t)y * srcStride, width);
        }
        return;
    }
    for (int y = 0; y < height; ++y) {
        int yMin = std::max(0, y - radius);
        int yMax = std::min(height - 1, y + radius);
        uint8_t* drow = dst + (size_t)y * dstStride;

        for (int x = 0; x < width; ++x) {
            int xMin = std::max(0, x - radius);
            int xMax = std::min(width - 1, x + radius);
            uint8_t maxV = 0;

            for (int ny = yMin; ny <= yMax && maxV < 255; ++ny) {
                const uint8_t* srow = src + (size_t)ny * srcStride;
                for (int nx = xMin; nx <= xMax; ++nx) {
                    if (srow[nx] > maxV) {
                        maxV = srow[nx];
                        if (maxV == 255) break;
                    }
                }
            }
            drow[x] = maxV;
        }
    }
}

JNIEXPORT void JNICALL
Java_com_mochits_core_imaging_NativeBridge_nativeDilateMask(
        JNIEnv* env,
        jobject /* this */,
        jobject srcMaskBitmap,
        jobject dstMaskBitmap,
        jint radius) {
    AndroidBitmapInfo srcInfo, dstInfo;
    void* srcPixels = nullptr;
    void* dstPixels = nullptr;

    if (AndroidBitmap_getInfo(env, srcMaskBitmap, &srcInfo) < 0 || srcInfo.format != ANDROID_BITMAP_FORMAT_A_8) return;
    if (AndroidBitmap_getInfo(env, dstMaskBitmap, &dstInfo) < 0 || dstInfo.format != ANDROID_BITMAP_FORMAT_A_8) return;

    int w = srcInfo.width;
    int h = srcInfo.height;
    if (dstInfo.width != w || dstInfo.height != h) return;

    if (AndroidBitmap_lockPixels(env, srcMaskBitmap, &srcPixels) < 0 || !srcPixels) return;
    if (AndroidBitmap_lockPixels(env, dstMaskBitmap, &dstPixels) < 0 || !dstPixels) {
        AndroidBitmap_unlockPixels(env, srcMaskBitmap);
        return;
    }

    int srcStride = srcInfo.stride;
    int dstStride = dstInfo.stride;

    if (radius <= 0) {
        for (int y = 0; y < h; ++y) {
            std::memcpy(
                static_cast<uint8_t*>(dstPixels) + (size_t)y * dstStride,
                static_cast<uint8_t*>(srcPixels) + (size_t)y * srcStride,
                w);
        }
    } else {
#ifdef HAVE_OPENCV
        bool ocvOk = false;
        try {
            cv::Mat srcMat(h, w, CV_8UC1, srcPixels, (size_t)srcStride);
            cv::Mat dstMat(h, w, CV_8UC1, dstPixels, (size_t)dstStride);
            int ksize = radius * 2 + 1;
            cv::Mat element = cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(ksize, ksize));
            cv::dilate(srcMat, dstMat, element);
            ocvOk = true;
        } catch (...) {
            LOGE("dilate OCV gagal (unknown), pakai loop tangan");
        }
        if (!ocvOk) {
            dilateMaskHand(
                static_cast<uint8_t*>(srcPixels), static_cast<uint8_t*>(dstPixels),
                w, h, srcStride, dstStride, radius);
        }
#else
        dilateMaskHand(
            static_cast<uint8_t*>(srcPixels), static_cast<uint8_t*>(dstPixels),
            w, h, srcStride, dstStride, radius);
#endif
    }

    AndroidBitmap_unlockPixels(env, dstMaskBitmap);
    AndroidBitmap_unlockPixels(env, srcMaskBitmap);
}

// Convert sRGB (0..255) to CIE Lab (L: 0..100, a: -128..127, b: -128..127)
static void rgbToLab(uint8_t r, uint8_t g, uint8_t b, float& L, float& a_val, float& b_val) {
    float rf = r / 255.0f;
    float gf = g / 255.0f;
    float bf = b / 255.0f;

    rf = (rf > 0.04045f) ? std::pow((rf + 0.055f) / 1.055f, 2.4f) : (rf / 12.92f);
    gf = (gf > 0.04045f) ? std::pow((gf + 0.055f) / 1.055f, 2.4f) : (gf / 12.92f);
    bf = (bf > 0.04045f) ? std::pow((bf + 0.055f) / 1.055f, 2.4f) : (bf / 12.92f);

    // D65 reference white
    float X = (rf * 0.4124564f + gf * 0.3575761f + bf * 0.1804375f) / 0.95047f;
    float Y = (rf * 0.2126729f + gf * 0.7151522f + bf * 0.0721750f) / 1.00000f;
    float Z = (rf * 0.0193339f + gf * 0.1191920f + bf * 0.9503041f) / 1.08883f;

    auto f = [](float t) -> float {
        return (t > 0.00885645167f) ? std::pow(t, 1.0f / 3.0f) : (7.787037037f * t + 16.0f / 116.0f);
    };

    float fx = f(X);
    float fy = f(Y);
    float fz = f(Z);

    L = (116.0f * fy) - 16.0f;
    a_val = 500.0f * (fx - fy);
    b_val = 200.0f * (fy - fz);
}

JNIEXPORT jlong JNICALL
Java_com_mochits_core_imaging_NativeBridge_nativeMagicWandSelect(
        JNIEnv* env,
        jobject /* this */,
        jobject srcBitmap,
        jobject maskBitmap,
        jint startX,
        jint startY,
        jfloat sensitivity) {
    // Status (32 bit bawah): 0 ok, 1 format citra, 2 ukuran mask,
    // 3 seed di luar, 4 kunci piksel gagal, 5 alokasi/proses gagal.
    // 32 bit atas: jumlah piksel terseleksi.
    AndroidBitmapInfo srcInfo, maskInfo;
    void* srcPixels = nullptr;
    void* maskPixels = nullptr;

    if (AndroidBitmap_getInfo(env, srcBitmap, &srcInfo) < 0 || srcInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return 1;
    if (AndroidBitmap_getInfo(env, maskBitmap, &maskInfo) < 0 || maskInfo.format != ANDROID_BITMAP_FORMAT_A_8) return 1;

    int width = srcInfo.width;
    int height = srcInfo.height;
    if (maskInfo.width != width || maskInfo.height != height) return 2;
    if (startX < 0 || startX >= width || startY < 0 || startY >= height) return 3;

    if (AndroidBitmap_lockPixels(env, srcBitmap, &srcPixels) < 0 || !srcPixels) return 4;
    if (AndroidBitmap_lockPixels(env, maskBitmap, &maskPixels) < 0 || !maskPixels) {
        AndroidBitmap_unlockPixels(env, srcBitmap);
        return 4;
    }

    int status = 0;
    size_t floodCount = 0;
    try {
        const int srcStridePx = srcInfo.stride / 4;
        const int maskStride = maskInfo.stride;
        uint32_t* srcBase = static_cast<uint32_t*>(srcPixels);
        uint8_t* maskBase = static_cast<uint8_t*>(maskPixels);

        // CLEAR raw mask first for REPLACE behavior (each tap overwrites selection)
        for (int y = 0; y < height; ++y) {
            std::memset(maskBase + (size_t)y * maskStride, 0, width);
        }

        // Map Sensitivity (0..100) -> CIE Lab deltaE threshold (1.0 .. 60.0)
        float deltaEThresh = 1.0f + (sensitivity / 100.0f) * 59.0f;

#ifdef HAVE_OPENCV
        bool ocvSuccess = false;
        try {
            cv::Mat rgbaMat(height, width, CV_8UC4, srcPixels, (size_t)srcInfo.stride);
            cv::Mat bgrMat, labMat;
            cv::cvtColor(rgbaMat, bgrMat, cv::COLOR_RGBA2BGR);
            cv::cvtColor(bgrMat, labMat, cv::COLOR_BGR2Lab);

            // Layer 2: Compute Sobel edge barrier
            cv::Mat grayMat, gradX, gradY, absGradX, absGradY, edgeMat;
            cv::cvtColor(bgrMat, grayMat, cv::COLOR_BGR2GRAY);
            cv::Sobel(grayMat, gradX, CV_16S, 1, 0, 3);
            cv::Sobel(grayMat, gradY, CV_16S, 0, 1, 3);
            cv::convertScaleAbs(gradX, absGradX);
            cv::convertScaleAbs(gradY, absGradY);
            cv::addWeighted(absGradX, 0.5, absGradY, 0.5, 0, edgeMat);

            // Create 1-pixel border mask required by cv::floodFill (size: height+2, width+2)
            cv::Mat fillMask = cv::Mat::zeros(height + 2, width + 2, CV_8UC1);

            // Mark high contrast edges (gradient > 45) as barrier (=1) in fillMask
            for (int r = 0; r < height; ++r) {
                const uint8_t* eRow = edgeMat.ptr<uint8_t>(r);
                uint8_t* mRow = fillMask.ptr<uint8_t>(r + 1);
                for (int c = 0; c < width; ++c) {
                    if (eRow[c] > 45) {
                        mRow[c + 1] = 1;
                    }
                }
            }
            // Ensure seed point in fillMask is open (0)
            fillMask.at<uint8_t>(startY + 1, startX + 1) = 0;

            // OpenCV cv::Lab scaling: L: 0..255 (L*255/100), a: 0..255 (a+128), b: 0..255 (b+128)
            // Scale deltaE threshold to OpenCV Lab space units (~255/100 = 2.55 scale)
            double labDiffVal = deltaEThresh * 2.55;
            cv::Scalar loDiff(labDiffVal, labDiffVal, labDiffVal);
            cv::Scalar upDiff(labDiffVal, labDiffVal, labDiffVal);

            cv::Rect rect;
            // FLOODFILL_MASK_ONLY: modifies fillMask by writing 255 to newly filled pixels
            cv::floodFill(labMat, fillMask, cv::Point(startX, startY), cv::Scalar(0), &rect,
                          loDiff, upDiff, 4 | (255 << 8) | cv::FLOODFILL_MASK_ONLY);

            // Copy filled mask results from fillMask to output ALPHA_8 maskBitmap
            for (int r = 0; r < height; ++r) {
                const uint8_t* mRow = fillMask.ptr<uint8_t>(r + 1);
                uint8_t* outRow = maskBase + (size_t)r * maskStride;
                for (int c = 0; c < width; ++c) {
                    if (mRow[c + 1] == 255) {
                        outRow[c] = 255;
                        ++floodCount;
                    }
                }
            }
            ocvSuccess = true;
        } catch (...) {
            LOGE("OpenCV floodFill failed, falling back to manual Lab BFS");
        }

        if (!ocvSuccess) {
#endif
            // Manual C++ BFS fallback with Lab deltaE & Sobel barrier
            uint32_t seedColor = srcBase[startY * srcStridePx + startX];
            uint8_t seedR = seedColor & 0xFF;
            uint8_t seedG = (seedColor >> 8) & 0xFF;
            uint8_t seedB = (seedColor >> 16) & 0xFF;


            float seedL, seedA, seedB_val;
            rgbToLab(seedR, seedG, seedB, seedL, seedA, seedB_val);

            // Compute manual Sobel barrier mask
            const size_t wh = (size_t)width * (size_t)height;
            std::vector<uint8_t> edgeBar(wh, 0);
            for (int y = 1; y < height - 1; ++y) {
                for (int x = 1; x < width - 1; ++x) {
                    // Gray value approximation
                    auto getGray = [&](int px, int py) -> int {
                        uint32_t c = srcBase[(size_t)py * srcStridePx + px];
                        return (int)(0.299f * (c & 0xFF) + 0.587f * ((c >> 8) & 0xFF) + 0.114f * ((c >> 16) & 0xFF));
                    };
                    int gx = -getGray(x-1,y-1) + getGray(x+1,y-1) - 2*getGray(x-1,y) + 2*getGray(x+1,y) - getGray(x-1,y+1) + getGray(x+1,y+1);
                    int gy = -getGray(x-1,y-1) - 2*getGray(x,y-1) - getGray(x+1,y-1) + getGray(x-1,y+1) + 2*getGray(x,y+1) + getGray(x+1,y+1);
                    int mag = (std::abs(gx) + std::abs(gy)) / 2;
                    if (mag > 45) {
                        edgeBar[(size_t)y * width + x] = 1;
                    }
                }
            }
            edgeBar[(size_t)startY * width + startX] = 0;

            std::vector<uint8_t> visited(wh, 0);
            std::queue<std::pair<int, int>> q;
            q.push({startX, startY});
            visited[(size_t)startY * width + startX] = 1;

            const int dx[4] = {0, 0, -1, 1};
            const int dy[4] = {-1, 1, 0, 0};

            while (!q.empty()) {
                auto [cx, cy] = q.front();
                q.pop();

                maskBase[(size_t)cy * maskStride + cx] = 255;
                ++floodCount;

                for (int i = 0; i < 4; ++i) {
                    int nx = cx + dx[i];
                    int ny = cy + dy[i];

                    if (nx >= 0 && nx < width && ny >= 0 && ny < height) {
                        size_t nIdx = (size_t)ny * width + nx;
                        if (!visited[nIdx]) {
                            visited[nIdx] = 1;
                            if (edgeBar[nIdx]) continue; // Stop at edge barrier

                            uint32_t c = srcBase[(size_t)ny * srcStridePx + nx];
                            uint8_t r = c & 0xFF;
                            uint8_t g = (c >> 8) & 0xFF;
                            uint8_t b = (c >> 16) & 0xFF;

                            float curL, curA, curB;
                            rgbToLab(r, g, b, curL, curA, curB);

                            float dL = curL - seedL;
                            float da = curA - seedA;
                            float db = curB - seedB_val;
                            float deltaE = std::sqrt(dL * dL + da * da + db * db);

                            if (deltaE <= deltaEThresh) {
                                q.push({nx, ny});
                            }
                        }
                    }
                }
            }
#ifdef HAVE_OPENCV
        }
#endif
    } catch (...) {
        LOGE("nativeMagicWandSelect failed");
        status = 5;
    }

    AndroidBitmap_unlockPixels(env, maskBitmap);
    AndroidBitmap_unlockPixels(env, srcBitmap);
    return (static_cast<jlong>(floodCount) << 32) | static_cast<jlong>(static_cast<uint32_t>(status));
}

JNIEXPORT void JNICALL
Java_com_mochits_core_imaging_NativeBridge_nativeClearMask(
        JNIEnv* env,
        jobject /* this */,
        jobject bitmap) {
    AndroidBitmapInfo info;
    void* pixels = nullptr;

    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return;
    if (info.format != ANDROID_BITMAP_FORMAT_A_8) return;

    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0 || !pixels) return;

    for (int y = 0; y < info.height; ++y) {
        std::memset(static_cast<uint8_t*>(pixels) + y * info.stride, 0, info.width);
    }

    AndroidBitmap_unlockPixels(env, bitmap);
}

} // extern "C"
