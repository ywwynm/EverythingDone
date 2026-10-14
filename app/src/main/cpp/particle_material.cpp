#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>
#include <array>
#include <condition_variable>
#include <deque>
#include <future>
#include <mutex>
#include <thread>
#include <atomic>
#include <cerrno>
#include <sys/resource.h>

namespace {
// 常驻计算线程加调用线程；多弹窗共用队列，避免每轮卷积重新建线程。
// 3b：道数改为按核数决定（3–6 道）。每一道只写自己那些下标、只读别的缓冲，所以道数
// 不改变任何元素的计算顺序，输出必然逐位相同（设备端 nativeCheck 用 1 道对照守住）。
constexpr int kMaxLanes=6;
int desiredLanes(){
    unsigned cores=std::thread::hardware_concurrency();
    if(cores==0)return 3;
    return int(std::min<unsigned>(std::max<unsigned>(cores,3u),kMaxLanes));
}
std::atomic<int> laneLimit{0};
// 第四批 ④：工作线程跟随**调用线程**的优先级。
//
// 3b 把道数从 3 提到 6，OPPO 建材 58 → 36–43 ms，三星 63–72 ms 纹丝不动——工作线程是默认
// 优先级（nice 0），而材料线程本身跑在 THREAD_PRIORITY_DISPLAY（nice −4），疑似被调度到小核。
// 这里改成每段工作开始前把自己的 nice 设成派发这段工作的线程的 nice：
//   - 材料线程在 DISPLAY 时，工作线程也是 DISPLAY，六道一起吃大核；
//   - 1.7 的「已经有触摸反馈或别的层在播时让出到 DEFAULT」照样成立——那时调用线程是 nice 0，
//     工作线程读到的就是 0，语义一个字没变，不需要在本地库里再判一次。
// `setpriority(PRIO_PROCESS,0,·)` 在 Linux 上作用于**当前线程**（tid），与
// `android.os.Process.setThreadPriority` 同义。
std::atomic<int> lanePriority{0};
int currentPriority(){
    errno=0;                       // nice 合法值含 −1，必须靠 errno 区分失败
    int value=getpriority(PRIO_PROCESS,0);
    return errno==0?value:0;
}
class MaterialWorkers {
    std::mutex mutex;
    std::condition_variable ready;
    std::deque<std::packaged_task<void()>> tasks;
    std::array<std::thread,kMaxLanes-1> workers;
    bool stopping=false;
public:
    MaterialWorkers(){try {for(auto& worker:workers)worker=std::thread([this]{
        setpriority(PRIO_PROCESS,0,0);
        for(;;){std::packaged_task<void()> task;
            {std::unique_lock<std::mutex> lock(mutex);ready.wait(lock,[&]{return stopping||!tasks.empty();});
                if(stopping&&tasks.empty())return;task=std::move(tasks.front());tasks.pop_front();}
            task();
        }
    });} catch (...) {
        {std::lock_guard<std::mutex> lock(mutex);stopping=true;}
        ready.notify_all();for(auto& worker:workers)if(worker.joinable())worker.join();throw;
    }}
    ~MaterialWorkers(){{std::lock_guard<std::mutex> lock(mutex);stopping=true;}ready.notify_all();for(auto& w:workers)w.join();}
    template<class F> std::future<void> submit(F function){std::packaged_task<void()> task(function);auto done=task.get_future();
        {std::lock_guard<std::mutex> lock(mutex);tasks.push_back(std::move(task));}ready.notify_one();return done;}
};
MaterialWorkers& workers(){static MaterialWorkers pool;return pool;}
int lanes(){int forced=laneLimit.load(std::memory_order_relaxed);
    return forced>0?std::min(forced,kMaxLanes):desiredLanes();}
template<class F> void parallel(int count,F function){
    int n=lanes();
    if(count<60||n<=1){for(int i=0;i<count;++i)function(i);return;}
    // 最后一段留给调用线程；每段的下标区间固定，与道数无关地覆盖同一批元素。
    std::array<std::future<void>,kMaxLanes-1> pending;
    int dispatched=0;
    const int priority=currentPriority();
    lanePriority.store(priority,std::memory_order_relaxed);
    try {
        for(int lane=0;lane<n-1;++lane){
            int from=int(int64_t(count)*lane/n),to=int(int64_t(count)*(lane+1)/n);
            pending[lane]=workers().submit([&,from,to,priority]{
                // 跟随调用线程；失败（受 RLIMIT_NICE 限制）时照常算，只是慢一点。
                setpriority(PRIO_PROCESS,0,priority);
                for(int i=from;i<to;++i)function(i);
            });
            ++dispatched;
        }
        for(int i=int(int64_t(count)*(n-1)/n);i<count;++i)function(i);
        for(int lane=0;lane<dispatched;++lane)pending[lane].get();
    } catch (...) {
        // 即使分配任务失败，也先结束已派发工作，避免访问调用栈中已销毁的数组。
        for(int lane=0;lane<dispatched;++lane)if(pending[lane].valid())pending[lane].wait();
        throw;
    }
}
template<class T> T clamp(T value, T lo, T hi) { return std::max(lo, std::min(value, hi)); }
float smooth(float t) { t=clamp(t,0.f,1.f); return t*t*(3.f-2.f*t); }
float randomValue(uint32_t i, uint32_t seed) {
    uint32_t v=i+seed; v=(v^(v>>16))*0x7feb352d; v=(v^(v>>15))*0x846ca68b;
    return float((v^(v>>16))>>8)/16777216.f;
}
using Float4 = float __attribute__((ext_vector_type(4)));
using Double4 = double __attribute__((ext_vector_type(4)));
Double4 load4(const float* input){Float4 value;std::memcpy(&value,input,sizeof(value));return __builtin_convertvector(value,Double4);}
void store4(float* output,Double4 value){Float4 result=__builtin_convertvector(value,Float4);std::memcpy(output,&result,sizeof(result));}
// 每个批次只跨 JNI 一次。正常数组访问允许 GC 移动/复制，不长时间持有 Critical 区。
struct Floats {
    JNIEnv* e; jfloatArray a; jfloat* p;
    Floats(JNIEnv* e,jfloatArray a):e(e),a(a),p(a?e->GetFloatArrayElements(a,nullptr):nullptr){}
    ~Floats(){if(p)e->ReleaseFloatArrayElements(a,p,JNI_ABORT);}
};
struct Doubles {
    JNIEnv* e; jdoubleArray a; jdouble* p;
    Doubles(JNIEnv* e,jdoubleArray a):e(e),a(a),p(e->GetDoubleArrayElements(a,nullptr)){}
    ~Doubles(){if(p)e->ReleaseDoubleArrayElements(a,p,JNI_ABORT);}
};
struct Ints {
    JNIEnv* e; jintArray a; jint* p;
    Ints(JNIEnv* e,jintArray a):e(e),a(a),p(e->GetIntArrayElements(a,nullptr)){}
    ~Ints(){if(p)e->ReleaseIntArrayElements(a,p,JNI_ABORT);}
};
jfloatArray array(JNIEnv* e,const std::vector<float>& v) {
    auto out=e->NewFloatArray(v.size());
    if(out)e->SetFloatArrayRegion(out,0,v.size(),v.data());
    return out;
}
}

#define JNI_NAME(name) Java_com_ywwynm_everythingdone_views_particledismiss_ParticleMaterialNative_##name
#define JNI_FAILURE \
    catch(const std::bad_alloc&) { e->ThrowNew(e->FindClass("java/lang/OutOfMemoryError"),"Particle material allocation"); return nullptr; } \
    catch(const std::exception& error) { e->ThrowNew(e->FindClass("java/lang/IllegalStateException"),error.what()); return nullptr; }

// 3b：只给 debug 的逐位对照用。道数不改变任何元素的计算顺序（每一道只写自己那些下标、
// 只读别的缓冲），所以 1 道与默认道数的输出必须逐位相同。
extern "C" JNIEXPORT void JNICALL JNI_NAME(nativeSetLanes)(JNIEnv*,jobject,jint lanes){
    laneLimit.store(lanes<=0?0:lanes,std::memory_order_relaxed);
}

extern "C" JNIEXPORT jint JNICALL JNI_NAME(nativeLanes)(JNIEnv*,jobject){ return lanes(); }

// 第四批 ④：工作线程最近一次采用的 nice，以及调用线程自己的 nice。
// 设备端 nativeCheck 在材料线程上跑一次并断言两者相等——「工作线程跟随调用线程」这条
// 没法靠输出逐位对照证明（优先级不改变任何数值），只能直接读回来。
extern "C" JNIEXPORT jint JNICALL JNI_NAME(nativeLanePriority)(JNIEnv*,jobject){
    return lanePriority.load(std::memory_order_relaxed);
}

extern "C" JNIEXPORT jint JNICALL JNI_NAME(nativeThreadPriority)(JNIEnv*,jobject){
    return currentPriority();
}

// 只给设备端对照用：真跑一次并行段（元素数必须超过 parallel 的串行阈值），再把工作线程
// 这一轮采用的 nice 读回来。用真实建材来触发不可靠——小尺寸下 parallel 走串行分支，
// 一道也不派发，读回来的永远是初值。
extern "C" JNIEXPORT jint JNICALL JNI_NAME(nativeProbeLanePriority)(JNIEnv*,jobject){
    lanePriority.store(-1000,std::memory_order_relaxed);
    std::vector<int> sink(4096,0);
    parallel(int(sink.size()),[&](int i){sink[i]=i;});
    return lanePriority.load(std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL JNI_NAME(packUploads)(JNIEnv* e,jobject,jintArray pixelsArray,
    jfloatArray materialArray,jint count,jobject rgbaBuffer,jobject stateBuffer){
    Ints pixels(e,pixelsArray);Floats material(e,materialArray);
    auto rgba=static_cast<uint32_t*>(e->GetDirectBufferAddress(rgbaBuffer));
    auto state=static_cast<float*>(e->GetDirectBufferAddress(stateBuffer));
    if(!pixels.p||!material.p||!rgba||!state)return;
    const int pixelCount=e->GetArrayLength(pixelsArray);
    for(int i=0;i<pixelCount;++i){uint32_t c=pixels.p[i];
        rgba[i]=(c&0xff00ff00)|((c>>16)&255)|((c&255)<<16);}
    std::fill_n(state,count*8,0.f);
    for(int i=0;i<count;++i){state[i*8]=material.p[i*12];state[i*8+1]=material.p[i*12+1];}
}

extern "C" JNIEXPORT void JNICALL JNI_NAME(packColors)(JNIEnv* e,jobject,jintArray pixelsArray,jobject rgbaBuffer){
    Ints pixels(e,pixelsArray);
    auto rgba=static_cast<uint32_t*>(e->GetDirectBufferAddress(rgbaBuffer));
    if(!pixels.p||!rgba)return;
    const int count=e->GetArrayLength(pixelsArray);
    for(int i=0;i<count;++i){uint32_t c=pixels.p[i];rgba[i]=(c&0xff00ff00)|((c>>16)&255)|((c&255)<<16);}
}

// 快照单拷贝（3.2）：Bitmap.copyPixelsToBuffer 得到的字节就是 R,G,B,A，可以直接上传纹理；
// 材料模型仍要 Android 的 ARGB int，所以从同一份字节反解一次。R 与 B 对调是对合运算，
// 与 packColors 是同一个变换，保证「上传的字节」与旧路径 packColors 的结果逐字节相同。
extern "C" JNIEXPORT void JNICALL JNI_NAME(unpackColors)(JNIEnv* e,jobject,jobject rgbaBuffer,jintArray pixelsArray){
    auto rgba=static_cast<const uint32_t*>(e->GetDirectBufferAddress(rgbaBuffer));
    if(!rgba||!pixelsArray)return;
    // Ints 那个 RAII 包装按 JNI_ABORT 释放（只读用途），写回必须自己提交。
    jint* out=e->GetIntArrayElements(pixelsArray,nullptr);
    if(!out)return;
    const int count=e->GetArrayLength(pixelsArray);
    for(int i=0;i<count;++i){uint32_t c=rgba[i];out[i]=(jint)((c&0xff00ff00)|((c>>16)&255)|((c&255)<<16));}
    e->ReleaseIntArrayElements(pixelsArray,out,0);
}

extern "C" JNIEXPORT void JNICALL JNI_NAME(packState)(JNIEnv* e,jobject,jfloatArray materialArray,jint count,jobject stateBuffer){
    Floats material(e,materialArray);
    auto state=static_cast<float*>(e->GetDirectBufferAddress(stateBuffer));
    if(!material.p||!state)return;
    std::fill_n(state,count*8,0.f);
    for(int i=0;i<count;++i){state[i*8]=material.p[i*12];state[i*8+1]=material.p[i*12+1];}
}

// 这两段仅搬运原来的逐材料循环；编译选项禁止 FMA 和 fast-math，保留 float 舍入边界。
extern "C" JNIEXPORT jfloat JNICALL JNI_NAME(bodyRelease)(JNIEnv* e,jobject,jfloatArray valuesArray,jfloat spread){
    Floats values(e,valuesArray);
    if(!values.p)return 0.f;
    float end=0.f;
    const int size=e->GetArrayLength(valuesArray);
    for(int i=0;i<size;i+=12){
        const float birth=values.p[i+2];
        const float source=birth-spread*(values.p[i+11]-.5f);
        const float tail=source+.060f;
        if(std::isnan(birth))return birth;
        if(std::isnan(tail))return tail;
        end=std::max(end,std::max(birth,tail));
    }
    return std::clamp(end+.001f,0.f,1.f);
}

// 面板分箱与白色像素统计共享原采样下标；这里只累计整数，比例与平滑仍在 Kotlin 中求值。
extern "C" JNIEXPORT jlongArray JNICALL JNI_NAME(classifyPixels)(JNIEnv* e,jobject,jintArray pixelsArray,
    jint width,jint height,jint stride){
    if(width<=0||height<=0||stride<=0||int64_t(width)*height!=e->GetArrayLength(pixelsArray)){
        e->ThrowNew(e->FindClass("java/lang/IllegalArgumentException"),"Invalid pixel dimensions");return nullptr;
    }
    Ints pixels(e,pixelsArray);
    if(!pixels.p)return nullptr;
    std::array<int,4096> counts{},first;
    std::array<std::array<int64_t,4096>,3> sums{};
    first.fill(INT32_MAX);
    jlong result[7]{};
    for(int y=0;y<height;y+=stride)for(int x=0;x<width;x+=stride){
        const int at=y*width+x;const uint32_t c=uint32_t(pixels.p[at]);
        const int alpha=c>>24,r=(c>>16)&255,g=(c>>8)&255,b=c&255;
        if(alpha>242){result[5]++;if(std::min(r,std::min(g,b))>229)result[6]++;}
        if(alpha!=255)continue;
        const int key=(r/16)*256+(g/16)*16+b/16;
        counts[key]++;first[key]=std::min(first[key],at);
        sums[0][key]+=r;sums[1][key]+=g;sums[2][key]+=b;result[0]++;
    }
    int best=0;
    for(int key=0;key<4096;++key)
        if(counts[key]>counts[best]||(counts[key]==counts[best]&&first[key]<first[best]))best=key;
    result[1]=counts[best];for(int i=0;i<3;++i)result[i+2]=sums[i][best];
    auto out=e->NewLongArray(7);if(out)e->SetLongArrayRegion(out,0,7,result);return out;
}

extern "C" JNIEXPORT jint JNICALL JNI_NAME(reverseRanges)(JNIEnv* e,jobject,jfloatArray valuesArray,
    jint samples,jintArray rangesArray){
    Floats values(e,valuesArray);
    if(!values.p)return 0;
    const int count=e->GetArrayLength(valuesArray)/12;
    if(samples<1||samples>60||e->GetArrayLength(rangesArray)!=count*4){
        e->ThrowNew(e->FindClass("java/lang/IllegalArgumentException"),"Invalid history dimensions");return 0;
    }
    jint* ranges=e->GetIntArrayElements(rangesArray,nullptr);
    if(!ranges)return 0;
    int64_t states=0;
    for(int i=0;i<count;++i){
        const float birth=values.p[i*12+2];
        const float death=birth+values.p[i*12+6];
        const float firstSample=birth*samples, lastSample=death*samples;
        const int first=int(std::clamp(std::floor(double(firstSample))-1.,0.,double(samples)));
        const int last=int(std::clamp(std::ceil(double(lastSample))+1.,double(first),double(samples)));
        ranges[i*4]=first;ranges[i*4+1]=last;ranges[i*4+2]=jint(states);ranges[i*4+3]=0;
        states+=last-first+1;
    }
    e->ReleaseIntArrayElements(rangesArray,ranges,0);
    if(states>INT32_MAX/20){
        e->ThrowNew(e->FindClass("java/lang/ArithmeticException"),"History size overflow");return 0;
    }
    return jint(states*20);
}

// 只移动取样循环到本地代码，避免冷启动时上万次 DirectByteBuffer 整字访问。
// 逐字节组合保证任意切片对齐及 LITTLE_ENDIAN 契约，位置和上限均由调用方原样保留。
extern "C" JNIEXPORT jintArray JNICALL JNI_NAME(gatherRgba)(JNIEnv* e,jobject,jobject rgbaBuffer,
    jint byteLimit,jintArray positionsArray) try {
    auto rgba=static_cast<const uint8_t*>(e->GetDirectBufferAddress(rgbaBuffer));
    const jlong capacity=e->GetDirectBufferCapacity(rgbaBuffer);
    if(!rgba || byteLimit<0 || byteLimit>capacity){
        e->ThrowNew(e->FindClass("java/lang/IllegalArgumentException"),"Invalid RGBA buffer limit");
        return nullptr;
    }
    Ints positions(e,positionsArray);
    if(!positions.p)return nullptr;
    const int count=e->GetArrayLength(positionsArray);
    std::vector<jint> values(count);
    for(int i=0;i<count;++i){
        const int64_t offset=int64_t(positions.p[i])*4;
        if(offset<0 || offset+4>byteLimit){
            e->ThrowNew(e->FindClass("java/lang/IndexOutOfBoundsException"),"RGBA sample outside buffer limit");
            return nullptr;
        }
        const uint8_t* p=rgba+offset;
        values[i]=jint(uint32_t(p[0])|(uint32_t(p[1])<<8)|(uint32_t(p[2])<<16)|(uint32_t(p[3])<<24));
    }
    auto result=e->NewIntArray(count);
    if(result)e->SetIntArrayRegion(result,0,count,values.data());
    return result;
} JNI_FAILURE

extern "C" JNIEXPORT jfloatArray JNICALL JNI_NAME(release)(JNIEnv* e,jobject,
    jint nx,jint ny,jfloat width,jfloat height,jint gw,jint gh,jfloatArray gridArray,
    jdouble low,jdouble size,jdoubleArray geometryArray,jdoubleArray anchorArray,
    jfloatArray variationArray,jdoubleArray inverseArray,jdoubleArray patchArray,
    jdouble wx,jdouble wy,jdouble locality,jfloatArray offsetsArray) try {
    Floats grid(e,gridArray), vf(e,variationArray);
    Doubles geom(e,geometryArray), anchors(e,anchorArray), inverse(e,inverseArray), patches(e,patchArray);
    if(!grid.p||!vf.p||!geom.p||!anchors.p||!inverse.p||!patches.p)return nullptr;
    double v[16];for(int i=0;i<16;++i)v[i]=vf.p[i];
    const auto g=geom.p; const int patchCount=e->GetArrayLength(patchArray)/6;
    const double span=std::min(width,height);
    std::vector<double> originals(nx*ny), details(nx*ny), local(nx*ny);
    std::vector<float> result(nx*ny), offsets(nx*ny);
    auto sample=[&](double x,double y) {
        double u=clamp(((.5+v[0]*x+v[2]*y+v[4]+v[6]*std::sin(v[9]*y+v[10]))-low)/size*gw-.5,0.,double(gw-1));
        double w=clamp(((.5+v[1]*y+v[3]*x+v[5]+v[7]*std::sin(v[8]*x+v[11]))-low)/size*gh-.5,0.,double(gh-1));
        int ix=int(u), iy=int(w), xx=std::min(ix+1,gw-1), yy=std::min(iy+1,gh-1);
        double ax=u-ix, ay=w-iy;
        double a=double(grid.p[iy*gw+ix])*(1-ax)+grid.p[iy*gw+xx]*ax;
        double b=double(grid.p[yy*gw+ix])*(1-ax)+grid.p[yy*gw+xx]*ax;
        return double(float(a*(1-ay)+b*ay));
    };
    auto delay=[&](double x,double y){return v[14]*std::sin(3.2*x+v[10])+v[15]*std::sin(3.6*y+v[11]);};
    parallel(ny,[&](int y){for(int x=0;x<nx;++x){
        int i=y*nx+x, k=(g[3]!=0?y:x)*5;
        double qx=((x+.5)/nx-.5)*width, qy=((y+.5)/ny-.5)*height;
        double xx=(qx-anchors.p[k])/g[0], yy=(qy-anchors.p[k+1])/g[1];
        double rx=g[4]*xx-g[5]*yy, ry=g[5]*xx+g[4]*yy;
        double field=sample(rx,ry), d=delay(rx+.5,ry+.5);
        if(g[2]>0){
            xx=(qx-anchors.p[k+2])/g[0];yy=(qy-anchors.p[k+3])/g[1];
            rx=g[4]*xx-g[5]*yy;ry=g[5]*xx+g[4]*yy;
            double weight=anchors.p[k+4];
            field=field*(1-weight)+sample(rx,ry)*weight;
            d=d*(1-weight)+delay(rx+.5,ry+.5)*weight;
        }
        double position=clamp(field,0.,1.)*1024;
        int index=std::min(int(position),1023);double fraction=position-index;
        double t=inverse.p[index]*(1-fraction)+inverse.p[index+1]*fraction;
        double time=t+t*(1-t)*(v[12]+v[13]*(2*t-1));
        double rate=1+v[12]*(1-2*t)+v[13]*(-6*t*t+6*t-1);
        originals[i]=clamp(t-(time-field)/rate,0.,1.);
        details[i]=originals[i]+d;
    }});
    parallel(ny,[&](int y){for(int x=0;x<nx;++x){
        int i=y*nx+x; double px=(x+.5)/nx*width,py=(y+.5)/ny*height,arrival=10.;
        for(int j=0;j<patchCount;++j){
            auto p=patches.p+j*6; double dx=(px-p[0])/span,dy=(py-p[1])/span;
            double u=(p[3]*dx+p[4]*dy)/p[5],w=(-p[4]*dx+p[3]*dy)/(p[5]*.80);
            double loc=p[2]+.62*std::sqrt(u*u+w*w+.0004)+.025*(dx*wx+dy*wy);
            double h=std::max(.055-std::abs(arrival-loc),0.)/.055;
            arrival=std::min(arrival,loc)-h*h*.055*.25;
        }
        arrival+=.035*std::tanh((clamp(details[i],.008,.78)-.38)/.18);
        local[i]=arrival;
    }});
    auto limits=std::minmax_element(local.begin(),local.end());
    double minArrival=*limits.first,maxArrival=*limits.second;
    double range=std::max(maxArrival-minArrival,1e-6);
    for(int i=0;i<nx*ny;++i){
        double base=clamp(details[i],.008,.78);
        double loc=.024+.72*std::pow((local[i]-minArrival)/range,1.45);
        double value=base+locality*(loc-base);
        result[i]=float(value);offsets[i]=float(value-originals[i]);
    }
    if(offsetsArray)e->SetFloatArrayRegion(offsetsArray,0,offsets.size(),offsets.data());
    return array(e,result);
} JNI_FAILURE

static std::vector<float> gaussian(const float* input,int width,int height,const double* weights,int length){
    int radius=length/2;
    auto reflect=[](int i,int n){while(i<0||i>=n)i=i<0?-i-1:2*n-i-1;return i;};
    std::vector<int> xi(width*length),yi(height*length);
    for(int x=0;x<width;++x)for(int k=0;k<length;++k)xi[x*length+k]=reflect(x+k-radius,width);
    for(int y=0;y<height;++y)for(int k=0;k<length;++k)yi[y*length+k]=reflect(y+k-radius,height)*width;
    std::vector<float> vertical(width*height),out(width*height);
    parallel(height,[&](int y){
        auto indices=yi.data()+y*length;int x=0;
        // 四个独立像素共用指令；每个像素内部的双精度累加顺序完全不变。
        for(;x+3<width;x+=4){Double4 value=load4(input+y*width+x)*weights[radius];
            for(int k=radius;k>=1;--k)value+=(load4(input+indices[radius-k]+x)+load4(input+indices[radius+k]+x))*weights[radius+k];
            store4(vertical.data()+y*width+x,value);
        }
        for(;x<width;++x){double value=input[y*width+x]*weights[radius];
            for(int k=radius;k>=1;--k)value+=(double(input[indices[radius-k]+x])+input[indices[radius+k]+x])*weights[radius+k];
            vertical[y*width+x]=float(value);
        }
    });
    parallel(height,[&](int y){int row=y*width;
        for(int x=0;x<width;){
            if(x>=radius && x+radius+3<width){Double4 value=load4(vertical.data()+row+x)*weights[radius];
                for(int k=radius;k>=1;--k)value+=(load4(vertical.data()+row+x-k)+load4(vertical.data()+row+x+k))*weights[radius+k];
                store4(out.data()+row+x,value);x+=4;
            } else {auto indices=xi.data()+x*length;double value=vertical[row+x]*weights[radius];
                for(int k=radius;k>=1;--k)value+=(double(vertical[row+indices[radius-k]])+vertical[row+indices[radius+k]])*weights[radius+k];
                out[row+x]=float(value);++x;
            }
        }
    });
    return out;
}

extern "C" JNIEXPORT jfloatArray JNICALL JNI_NAME(gaussian)(JNIEnv* e,jobject,
    jfloatArray inputArray,jint width,jint height,jdoubleArray weightArray) try {
    Floats input(e,inputArray);Doubles weights(e,weightArray);
    if(!input.p||!weights.p)return nullptr;
    return array(e,gaussian(input.p,width,height,weights.p,e->GetArrayLength(weightArray)));
} JNI_FAILURE

extern "C" JNIEXPORT jobjectArray JNICALL JNI_NAME(finishField)(JNIEnv* e,jobject,
    jfloatArray fieldArray,jint nx,jint ny,jfloat width,jfloat height,jdouble wx,jdouble wy,
    jfloat panelWeight,jdoubleArray w1Array,jdoubleArray w3Array,jdoubleArray w7Array) try {
    Floats field(e,fieldArray);Doubles w1(e,w1Array),w3(e,w3Array),w7(e,w7Array);
    if(!field.p||!w1.p||!w3.p||!w7.p)return nullptr;
    int n=nx*ny;float cx=width/nx,cy=height/ny;double span=std::min(width,height);
    auto blurred=gaussian(field.p,nx,ny,w3.p,e->GetArrayLength(w3Array));
    std::vector<double> edgeX(nx),edgeY(ny),againstX(nx),againstY(ny);
    auto sm=[](double t){t=clamp(t,0.,1.);return t*t*(3-2*t);};
    for(int x=0;x<nx;++x){edgeX[x]=std::min((x+.5)/nx*width,width-(x+.5)/nx*width);
        againstX[x]=sm((x+.5<nx*.5?wx:-wx)/.65)*std::exp(-edgeX[x]/(span*.14));}
    for(int y=0;y<ny;++y){edgeY[y]=std::min((y+.5)/ny*height,height-(y+.5)/ny*height);
        againstY[y]=sm((y+.5<ny*.5?wy:-wy)/.65)*std::exp(-edgeY[y]/(span*.14));}
    double coreGain=.025*(1.-.65*panelWeight);
    std::vector<float> refined(n),gx(n),gy(n);
    parallel(ny,[&](int y){for(int x=0;x<nx;++x){int i=y*nx+x;
        float dx=(blurred[y*nx+std::min(x+1,nx-1)]-blurred[y*nx+std::max(x-1,0)])/(cx*(x==0||x==nx-1?1:2));
        float dy=(blurred[std::min(y+1,ny-1)*nx+x]-blurred[std::max(y-1,0)*nx+x])/(cy*(y==0||y==ny-1?1:2));
        float norm=std::max(float(std::hypot(double(dx),double(dy))),1e-8f);
        double against=std::max(againstX[x],againstY[y])*sm(((dx*wx+dy*wy)/norm-.40)/.50);
        double interior=1-std::exp(-std::min(edgeX[x],edgeY[y])/(span*.10));
        float late=smooth((field.p[i]-.40f)/.17f),ready=smooth((field.p[i]-.23f)/.25f);
        refined[i]=float(field.p[i]+coreGain*interior*late-.065*against*ready);
    }});
    blurred=gaussian(refined.data(),nx,ny,w3.p,e->GetArrayLength(w3Array));
    parallel(ny,[&](int y){for(int x=0;x<nx;++x){int i=y*nx+x;
        float dx=(blurred[y*nx+std::min(x+1,nx-1)]-blurred[y*nx+std::max(x-1,0)])/(cx*(x==0||x==nx-1?1:2));
        float dy=(blurred[std::min(y+1,ny-1)*nx+x]-blurred[std::max(y-1,0)*nx+x])/(cy*(y==0||y==ny-1?1:2));
        float length=std::max(float(std::hypot(double(dx),double(dy))),1e-6f);gx[i]=dx/length;gy[i]=dy/length;
    }});
    auto normalX=gaussian(gx.data(),nx,ny,w7.p,e->GetArrayLength(w7Array));
    auto normalY=gaussian(gy.data(),nx,ny,w7.p,e->GetArrayLength(w7Array));
    // 逐元素、无邻域依赖，按行并行不改变任何一个值。
    parallel(n,[&](int i){float x=-normalX[i],y=-normalY[i];double along=x*wx+y*wy;
        x=float(x-std::min(along,0.)*wx);y=float(y-std::min(along,0.)*wy);
        gx[i]=float(x-std::max(along,0.)*wx*.82);gy[i]=float(y-std::max(along,0.)*wy*.82);
    });
    std::vector<float> compression(n);
    parallel(ny,[&](int y){for(int x=0;x<nx;++x){int i=y*nx+x;
        int l=y*nx+std::max(x-1,0),r=y*nx+std::min(x+1,nx-1),t=std::max(y-1,0)*nx+x,b=std::min(y+1,ny-1)*nx+x;
        float sx=cx*(x==0||x==nx-1?1:2),sy=cy*(y==0||y==ny-1?1:2);
        float a=(gx[r]-gx[l])/sx,d=(gy[b]-gy[t])/sy;
        float v=((gy[r]-gy[l])/sx+(gx[b]-gx[t])/sy)*.5f,half=(a-d)*.5f;
        float lowest=(a+d)*.5f-float(std::sqrt(double(half*half+v*v)));
        compression[i]=std::max(-lowest,0.f);
    }});
    compression=gaussian(compression.data(),nx,ny,w1.p,e->GetArrayLength(w1Array));
    for(float& v:compression)v*=float(span);
    auto type=e->FindClass("[F");auto result=e->NewObjectArray(4,type,nullptr);
    auto a=array(e,refined),b=array(e,normalX),c=array(e,normalY),d=array(e,compression);
    if(result&&a&&b&&c&&d){e->SetObjectArrayElement(result,0,a);e->SetObjectArrayElement(result,1,b);
        e->SetObjectArrayElement(result,2,c);e->SetObjectArrayElement(result,3,d);}
    return result;
} JNI_FAILURE

extern "C" JNIEXPORT jobjectArray JNICALL JNI_NAME(populate)(JNIEnv* e,jobject,
    jint nx,jint ny,jfloat width,jfloat height,jintArray pixelArray,jint pw,jint ph,jint seed,
    jfloatArray releaseArray,jfloatArray offsetArray,jfloatArray normalXArray,jfloatArray normalYArray,
    jfloatArray compressionArray,jfloatArray panelArray,jfloatArray ruleArray,jint copyLimit,jint maxCount) try {
    Floats release(e,releaseArray),offset(e,offsetArray),normalX(e,normalXArray),normalY(e,normalYArray),
        compression(e,compressionArray),panel(e,panelArray),rules(e,ruleArray);
    if(!release.p||!offset.p||!normalX.p||!normalY.p||!compression.p||!panel.p||!rules.p)return nullptr;
    Ints pixels(e,pixelArray);if(!pixels.p)return nullptr;
    const int count=nx*ny; const float cx=width/nx,cy=height/ny;auto r=rules.p;
    std::vector<float> values(count*12),pigment(count);
    std::vector<uint32_t> colors(count);
    auto life=[&](float rx,float rz,float born,float content){
        float cap=.865f+.115f*rz-born;
        float gain=r[2]+(r[1]-r[2])*smooth((born-r[3])/r[4]);
        // Kotlin Float ln/pow 先用 double 计算，再逐步转回 float。
        float power=float(std::pow(double(-float(std::log(double(std::max(rx,.004f))))),double(.85f)));
        float result=(.10f+.22f*power+.20f*born)*gain;
        result=std::max(std::min(result,cap),.11f);
        return std::max(std::min(result*(1.f+.30f*content),cap),.11f);
    };
    parallel(count,[&](int i){
        float x=(i%nx+.5f)*cx,y=(i/nx+.5f)*cy;
        float rx=randomValue(i*4,seed),ry=randomValue(i*4+1,seed),rz=randomValue(i*4+2,seed),rw=randomValue(i*4+3,seed);
        float born=std::max(release.p[i]+r[0]*(rw-.5f),.001f);
        int px=clamp(int(x/width*pw),0,pw-1),py=clamp(int(y/height*ph),0,ph-1);
        uint32_t c=pixels.p[py*pw+px];colors[i]=c;
        float red=((c>>16)&255)/255.f,green=((c>>8)&255)/255.f,blue=(c&255)/255.f;
        float chroma=smooth((std::max(red,std::max(green,blue))-std::min(red,std::min(green,blue))-.30f)/.42f);
        float delta=std::max(std::abs(red-panel.p[0]),std::max(std::abs(green-panel.p[1]),std::abs(blue-panel.p[2])));
        float contrast=smooth((delta-r[5])/r[6]);
        float content=(c>>24)==0?0.f:(chroma+(contrast-chroma)*panel.p[3])*((c>>24)/255.f);
        auto p=values.data()+i*12;
        p[0]=x;p[1]=y;p[2]=born;p[3]=float(i);p[6]=life(rx,rz,born,content);p[7]=offset.p[i];
        p[8]=rx;p[9]=ry;p[10]=rz;p[11]=rw;pigment[i]=content;
    });
    std::vector<int> candidates;
    for(int layer=1;layer<=copyLimit;++layer)for(int i=0;i<count;++i){
        int id=i+layer*count;float amount=pigment[i]*(copyLimit*panel.p[3]);
        if((colors[i]>>24)==255&&randomValue(id*4,seed)<amount-(layer-1))candidates.push_back(id);
    }
    int capacity=std::max(maxCount-count,0);
    if(int(candidates.size())>capacity){
        std::sort(candidates.begin(),candidates.end(),[&](int a,int b){
            float x=randomValue(a*4+1,seed),y=randomValue(b*4+1,seed);return x==y?a<b:x<y;
        });candidates.resize(capacity);
    }
    std::sort(candidates.begin(),candidates.end());
    int total=count+candidates.size();values.resize(total*12);pigment.resize(total);
    for(int i=0;i<int(candidates.size());++i){
        int id=candidates[i],source=id%count;auto p=values.data()+(count+i)*12;
        std::copy_n(values.data()+source*12,12,p);p[3]=float(id);
        for(int k=0;k<4;++k)p[8+k]=randomValue(id*4+k,seed);
        p[6]=life(p[8],p[10],p[2],pigment[source]);pigment[count+i]=pigment[source];
    }
    std::vector<int64_t> order(total);
    for(int i=0;i<total;++i){auto p=values.data()+i*12;
        float depth=float(std::sin(double(p[0]*.014f+p[1]*.021f)))*.6f+(p[9]-.5f)*.15f;
        order[i]=(int64_t((depth+1.f)*1000000)<<32)|i;
    }
    // 深度键最多 21 位；初始索引已升序，三轮稳定基数排序与原 64 位排序完全等价。
    std::vector<int64_t> scratch(total);
    for(int shift=32;shift<=48;shift+=8){
        std::array<int,256> bins{};
        for(auto key:order)++bins[(key>>shift)&255];
        int offset=0;for(auto& size:bins){int count=size;size=offset;offset+=count;}
        for(auto key:order)scratch[bins[(key>>shift)&255]++]=key;
        order.swap(scratch);
    }
    std::vector<float> sorted(total*12),sp(total),sc(total);
    // 收束也是逐元素：第 i 条只写 sorted[i*12..]、sp[i]、sc[i]，读的是排序前的数组。
    parallel(total,[&](int i){int old=int(order[i]&0xffffffff);auto p=sorted.data()+i*12;
        std::copy_n(values.data()+old*12,12,p);int source=int(p[3])%count;
        p[4]=normalX.p[source];p[5]=normalY.p[source];sp[i]=pigment[old];sc[i]=compression.p[source];
    });
    auto type=e->FindClass("[F");auto result=e->NewObjectArray(3,type,nullptr);
    auto a=array(e,sorted),b=array(e,sp),c=array(e,sc);
    if(result&&a&&b&&c){e->SetObjectArrayElement(result,0,a);e->SetObjectArrayElement(result,1,b);e->SetObjectArrayElement(result,2,c);}
    return result;
} JNI_FAILURE
