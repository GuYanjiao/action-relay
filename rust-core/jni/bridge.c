/* Transport glue only. Protocol, CRC, reassembly and queue policy live in Rust. */
#include <jni.h>
#include <stdint.h>
#include <stddef.h>
extern size_t relay_stream_size(void), relay_queue_size(void);
extern uint32_t relay_crc(const uint8_t *,size_t,int32_t);
extern int32_t relay_valid(const uint8_t *,size_t);
extern intptr_t relay_frame(uint8_t,uint8_t,uint8_t,uint16_t,const uint8_t*,size_t,uint8_t*,size_t);
extern intptr_t relay_feed(uint8_t*,size_t,const uint8_t*,size_t,uint8_t*,size_t);
extern int32_t relay_offer(uint8_t*,size_t,const uint8_t*,size_t,int64_t,int32_t);
extern intptr_t relay_poll(uint8_t*,size_t,int64_t,uint8_t*,size_t);
extern int32_t relay_count(const uint8_t*,size_t),relay_clear(uint8_t*,size_t,int32_t);
static void error(JNIEnv *e,const char *kind,const char *message){
    if((*e)->ExceptionCheck(e))return;
    jclass c=(*e)->FindClass(e,kind);if(c){(*e)->ThrowNew(e,c,message);(*e)->DeleteLocalRef(e,c);}
}
static int read_bytes(JNIEnv *e,jbyteArray a,uint8_t *out,int max,int optional){
    if(!a){if(optional)return 0;error(e,"java/lang/IllegalArgumentException","Null input");return -1;}
    jsize n=(*e)->GetArrayLength(e,a);
    if(n>max){error(e,"java/lang/IllegalArgumentException","Input exceeds bounded capacity");return -1;}
    if(n)(*e)->GetByteArrayRegion(e,a,0,n,(jbyte*)out);
    return (*e)->ExceptionCheck(e)?-1:n;
}
static uint8_t *state(JNIEnv *e,jobject b,size_t *size){
    if(!b){error(e,"java/lang/IllegalArgumentException","Missing direct state");return NULL;}
    jlong n=(*e)->GetDirectBufferCapacity(e,b);uint8_t *p=(*e)->GetDirectBufferAddress(e,b);
    if(!p||n<0||n>65536){error(e,"java/lang/IllegalArgumentException","Invalid direct state");return NULL;}
    *size=(size_t)n;return p;
}
static jbyteArray result(JNIEnv *e,uint8_t *bytes,intptr_t n){
    if(n<0){error(e,"java/lang/IllegalArgumentException","Rejected native core input/state");return NULL;}
    jbyteArray a=(*e)->NewByteArray(e,(jsize)n);
    if(a&&n)(*e)->SetByteArrayRegion(e,a,0,(jsize)n,(jbyte*)bytes);return a;
}
#define FN(name) Java_cn_local_actionrelay_core_NativeCore_##name
JNIEXPORT jintArray JNICALL FN(sizes)(JNIEnv *e,jclass c){
    jint v[2]={(jint)relay_stream_size(),(jint)relay_queue_size()};jintArray a=(*e)->NewIntArray(e,2);
    if(a)(*e)->SetIntArrayRegion(e,a,0,2,v);return a;
}
JNIEXPORT jlong JNICALL FN(crc)(JNIEnv *e,jclass c,jbyteArray a,jint off,jint len,jboolean wide){
    uint8_t b[8192];
    if(!a||off<0||len<0||len>8192||off>(*e)->GetArrayLength(e,a)-len){error(e,"java/lang/IllegalArgumentException","Invalid CRC range");return 0;}
    if(len)(*e)->GetByteArrayRegion(e,a,off,len,(jbyte*)b);
    if((*e)->ExceptionCheck(e))return 0;return (jlong)relay_crc(b,(size_t)len,wide);
}
JNIEXPORT jbyteArray JNICALL FN(frame)(JNIEnv *e,jclass c,jint set,jint id,jint kind,jint seq,jbyteArray a){
    uint8_t in[1023],out[1023];int n=read_bytes(e,a,in,1005,1);if(n<0)return NULL;
    return result(e,out,relay_frame((uint8_t)set,(uint8_t)id,(uint8_t)kind,(uint16_t)seq,in,(size_t)n,out,sizeof out));
}
JNIEXPORT void JNICALL FN(validate)(JNIEnv *e,jclass c,jbyteArray a){
    uint8_t b[1023];int n=read_bytes(e,a,b,sizeof b,0);if(n<0)return;
    if(!relay_valid(b,(size_t)n))error(e,"java/lang/IllegalArgumentException","Invalid DJI frame or CRC");
}
JNIEXPORT jbyteArray JNICALL FN(feed)(JNIEnv *e,jclass c,jobject buffer,jbyteArray a){
    size_t size;uint8_t *s=state(e,buffer,&size);if(!s)return NULL;
    uint8_t in[8192],out[12000];int n=read_bytes(e,a,in,sizeof in,1);if(n<0)return NULL;
    return result(e,out,relay_feed(s,size,in,(size_t)n,out,sizeof out));
}
JNIEXPORT void JNICALL FN(offer)(JNIEnv *e,jclass c,jobject buffer,jbyteArray a,jlong now,jboolean gps){
    size_t size;uint8_t *s=state(e,buffer,&size);if(!s)return;
    uint8_t in[1023];int n=read_bytes(e,a,in,sizeof in,0);if(n<0)return;
    int r=relay_offer(s,size,in,(size_t)n,now,gps);
    if(r)error(e,r==-2?"java/lang/IllegalStateException":"java/lang/IllegalArgumentException",r==-2?"Camera command queue full":"Rejected queue input");
}
JNIEXPORT jbyteArray JNICALL FN(poll)(JNIEnv *e,jclass c,jobject buffer,jlong now){
    size_t size;uint8_t *s=state(e,buffer,&size);if(!s)return NULL;uint8_t out[1040];
    return result(e,out,relay_poll(s,size,now,out,sizeof out));
}
JNIEXPORT void JNICALL FN(clear)(JNIEnv *e,jclass c,jobject buffer,jboolean gps){
    size_t size;uint8_t *s=state(e,buffer,&size);if(!s)return;
    if(relay_clear(s,size,gps))error(e,"java/lang/IllegalArgumentException","Invalid state capacity");
}
JNIEXPORT jint JNICALL FN(count)(JNIEnv *e,jclass c,jobject buffer){
    size_t size;uint8_t *s=state(e,buffer,&size);if(!s)return 0;int n=relay_count(s,size);
    if(n<0)error(e,"java/lang/IllegalArgumentException","Invalid queue state");return n;
}
