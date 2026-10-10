#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include <sys/stat.h>
#include "esp_loader.h"
#include "esp_loader_io.h"

typedef struct {esp_loader_port_t base;JNIEnv *env;jobject object;jmethodID read,write,baud,reset,progress;uint64_t deadline;int failed;} JavaPort;
static uint64_t now_ms(void){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return (uint64_t)t.tv_sec*1000+t.tv_nsec/1000000;}
static JavaPort *java_port(esp_loader_port_t *p){return (JavaPort *)p;}
static int pending(JavaPort *p){if((*p->env)->ExceptionCheck(p->env)){(*p->env)->ExceptionClear(p->env);p->failed=1;}return p->failed;}
static esp_loader_error_t transfer(esp_loader_port_t *port,uint8_t *data,uint16_t size,uint32_t timeout,int write){
    JavaPort *p=java_port(port);if(p->failed)return ESP_LOADER_ERROR_FAIL;jbyteArray b=(*p->env)->NewByteArray(p->env,size);if(!b||pending(p))return ESP_LOADER_ERROR_FAIL;
    if(write)(*p->env)->SetByteArrayRegion(p->env,b,0,size,(jbyte *)data);
    jint n=(*p->env)->CallIntMethod(p->env,p->object,write?p->write:p->read,b,(jint)timeout);
    if(!pending(p)&&!write&&n==size)(*p->env)->GetByteArrayRegion(p->env,b,0,size,(jbyte *)data);
    (*p->env)->DeleteLocalRef(p->env,b);return pending(p)?ESP_LOADER_ERROR_FAIL:n==size?ESP_LOADER_SUCCESS:ESP_LOADER_ERROR_TIMEOUT;
}
static esp_loader_error_t read_port(esp_loader_port_t *p,uint8_t *b,uint16_t n,uint32_t timeout){return transfer(p,b,n,timeout,0);}
static esp_loader_error_t write_port(esp_loader_port_t *p,const uint8_t *b,uint16_t n,uint32_t timeout){return transfer(p,(uint8_t *)b,n,timeout,1);}
static void reset_port(esp_loader_port_t *port,int boot){JavaPort *p=java_port(port);if(!p->failed)(*p->env)->CallVoidMethod(p->env,p->object,p->reset,(jboolean)boot);pending(p);}
static void boot(esp_loader_port_t *p){reset_port(p,1);}static void reset(esp_loader_port_t *p){reset_port(p,0);}
static void timer(esp_loader_port_t *port,uint32_t ms){java_port(port)->deadline=now_ms()+ms;}
static uint32_t remaining(esp_loader_port_t *port){uint64_t end=java_port(port)->deadline,now=now_ms();return end>now?(uint32_t)(end-now):0;}
static void delay(esp_loader_port_t *port,uint32_t ms){(void)port;struct timespec t={ms/1000,(ms%1000)*1000000};nanosleep(&t,NULL);}
static esp_loader_error_t baud(esp_loader_port_t *port,uint32_t rate){JavaPort *p=java_port(port);if(!p->failed)(*p->env)->CallVoidMethod(p->env,p->object,p->baud,(jint)rate);return pending(p)?ESP_LOADER_ERROR_FAIL:ESP_LOADER_SUCCESS;}
static const esp_loader_port_ops_t operations={.enter_bootloader=boot,.reset_target=reset,.start_timer=timer,.remaining_time=remaining,.delay_ms=delay,.change_transmission_rate=baud,.write=write_port,.read=read_port};
JNIEXPORT jstring JNICALL Java_dev_mateuszsury_androidagent_EspFlasher_operate(JNIEnv *env,jclass cls,jobject object,jobjectArray files,jlongArray offsets,jint expected){
    (void)cls;JavaPort port={.base={.ops=&operations},.env=env,.object=object};jclass c=(*env)->GetObjectClass(env,object);
    port.read=(*env)->GetMethodID(env,c,"read","([BI)I");port.write=(*env)->GetMethodID(env,c,"write","([BI)I");port.baud=(*env)->GetMethodID(env,c,"baud","(I)V");port.reset=(*env)->GetMethodID(env,c,"reset","(Z)V");port.progress=(*env)->GetMethodID(env,c,"progress","(J)V");
    if((*env)->ExceptionCheck(env))return NULL;
    esp_loader_t loader;esp_loader_error_t code=esp_loader_init_serial(&loader,&port.base);int initialized=(code==ESP_LOADER_SUCCESS);esp_loader_connect_args_t args=ESP_LOADER_CONNECT_DEFAULT();
    if(code==ESP_LOADER_SUCCESS)code=esp_loader_connect(&loader,&args);
    int count=(*env)->GetArrayLength(env,files);target_chip_t chip=initialized?esp_loader_get_target(&loader):ESP_UNKNOWN_CHIP;uint32_t size=0;esp_loader_target_security_info_t security={0};int security_result=ESP_LOADER_ERROR_FAIL;
    if(code==ESP_LOADER_SUCCESS){security_result=esp_loader_get_security_info(&loader,&security);code=esp_loader_flash_detect_size(&loader,&size);}
    if(code==ESP_LOADER_SUCCESS&&count>0){
        if(expected<0||expected!=chip||security_result!=ESP_LOADER_SUCCESS||security.secure_boot_enabled||security.flash_encryption_enabled||security.secure_download_mode_enabled)code=ESP_LOADER_ERROR_INVALID_TARGET;
    }
    uint64_t written=0;
    for(int i=0;i<count&&code==ESP_LOADER_SUCCESS;i++){
        jstring name=(*env)->GetObjectArrayElement(env,files,i);const char *path=(*env)->GetStringUTFChars(env,name,NULL);jlong offset;(*env)->GetLongArrayRegion(env,offsets,i,1,&offset);FILE *file=fopen(path,"rb");(*env)->ReleaseStringUTFChars(env,name,path);(*env)->DeleteLocalRef(env,name);
        struct stat metadata;if(!file||fstat(fileno(file),&metadata)!=0||metadata.st_size<=0||offset<0||offset>size||metadata.st_size>size-offset){if(file)fclose(file);code=ESP_LOADER_ERROR_IMAGE_SIZE;break;}
        esp_loader_flash_cfg_t cfg={.offset=(uint32_t)offset,.image_size=(uint32_t)metadata.st_size,.block_size=1024,.skip_verify=false};code=esp_loader_flash_start(&loader,&cfg);uint8_t buffer[1024];size_t n;
        while(code==ESP_LOADER_SUCCESS&&(n=fread(buffer,1,sizeof(buffer),file))>0){code=esp_loader_flash_write(&loader,&cfg,buffer,(uint32_t)n);written+=n;(*env)->CallVoidMethod(env,object,port.progress,(jlong)written);if(pending(&port))code=ESP_LOADER_ERROR_FAIL;}
        if(ferror(file))code=ESP_LOADER_ERROR_FAIL;fclose(file);if(code==ESP_LOADER_SUCCESS)code=esp_loader_flash_finish(&loader,&cfg);
    }
    if(code==ESP_LOADER_SUCCESS&&count>0)esp_loader_reset_target(&loader);
    if(initialized)esp_loader_deinit(&loader);
    if(port.failed||code!=ESP_LOADER_SUCCESS){char error[256];snprintf(error,sizeof(error),"ESP operation failed (code %d%s). Programming may be partial; no success was recorded.",code,port.failed?", I/O cancelled or failed":"");(*env)->ThrowNew(env,(*env)->FindClass(env,"java/io/IOException"),error);return NULL;}
    char result[512];snprintf(result,sizeof(result),"{\"chip\":%d,\"flash_bytes\":%u,\"security_info_available\":%s,\"secure_boot\":%s,\"flash_encryption\":%s,\"written\":%llu,\"verified\":%s}",chip,size,security_result==ESP_LOADER_SUCCESS?"true":"false",security.secure_boot_enabled?"true":"false",security.flash_encryption_enabled?"true":"false",(unsigned long long)written,count>0?"true":"false");return (*env)->NewStringUTF(env,result);
}
