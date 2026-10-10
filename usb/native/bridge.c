#include <jni.h>
#include <libusb.h>
#include <usbredirhost.h>
#include <pthread.h>
#include <stdatomic.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/stat.h>
#include <poll.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
    libusb_context *ctx;libusb_device_handle *device;struct usbredirhost *host;
    int descriptor,listen;pthread_t thread;atomic_int client,stopping,finished;char path[108],error[256];
} Bridge;
static void fail(JNIEnv *env,const char *message){(*env)->ThrowNew(env,(*env)->FindClass(env,"java/io/IOException"),message);}
static void log_message(void *opaque,int level,const char *message){Bridge *b=opaque;if(level<=usbredirparser_warning)snprintf(b->error,sizeof(b->error),"%s",message);}
static int read_guest(void *opaque,uint8_t *data,int count){Bridge *b=opaque;int n=recv(b->client,data,count,MSG_DONTWAIT);return n<0&&(errno==EAGAIN||errno==EWOULDBLOCK)?0:n==0?-1:n;}
static int write_guest(void *opaque,uint8_t *data,int count){Bridge *b=opaque;int n=send(b->client,data,count,MSG_DONTWAIT|MSG_NOSIGNAL);return n<0&&(errno==EAGAIN||errno==EWOULDBLOCK)?0:n;}
static void *serve(void *opaque){Bridge *b=opaque;
    while(!atomic_load(&b->stopping)){struct pollfd p={b->listen,POLLIN,0};if(poll(&p,1,100)>0){b->client=accept4(b->listen,NULL,NULL,SOCK_CLOEXEC|SOCK_NONBLOCK);if(b->client>=0)break;}}
    if(b->client>=0&&!atomic_load(&b->stopping)){
        b->host=usbredirhost_open(b->ctx,b->device,log_message,read_guest,write_guest,b,"androidagent USB",usbredirparser_warning,0);b->device=NULL;
        if(!b->host)snprintf(b->error,sizeof(b->error),"Cannot redirect the selected USB device");
        while(b->host&&!atomic_load(&b->stopping)){
            struct pollfd p={b->client,POLLIN|POLLERR|POLLHUP,0};int n=poll(&p,1,10);if(n<0&&errno!=EINTR)break;
            if(n>0&&(p.revents&(POLLHUP|POLLERR)))break;
            if(n>0&&(p.revents&POLLIN)&&usbredirhost_read_guest_data(b->host)<0)break;
            struct timeval timeout={0,1000};int result=libusb_handle_events_timeout(b->ctx,&timeout);if(result<0&&result!=LIBUSB_ERROR_INTERRUPTED)break;
            if(usbredirhost_has_data_to_write(b->host)&&usbredirhost_write_guest_data(b->host)<0)break;
        }
    }
    if(b->host){usbredirhost_close(b->host);b->host=NULL;}if(b->device){libusb_close(b->device);b->device=NULL;}
    atomic_store(&b->finished,1);return NULL;
}
JNIEXPORT jlong JNICALL Java_dev_mateuszsury_androidagent_UsbNative_start(JNIEnv *env,jclass cls,jint fd,jstring path){
    (void)cls;const char *name=(*env)->GetStringUTFChars(env,path,NULL);Bridge *b=calloc(1,sizeof(*b));if(!b){(*env)->ReleaseStringUTFChars(env,path,name);fail(env,"Out of memory");return 0;}
    b->listen=b->client=b->descriptor=-1;
    if(strlen(name)>=sizeof(b->path)){fail(env,"USB socket path too long");goto error;}strcpy(b->path,name);(*env)->ReleaseStringUTFChars(env,path,name);name=NULL;
    b->descriptor=dup(fd);if(b->descriptor<0){fail(env,"Invalid Android USB descriptor");goto error;}
    libusb_set_option(NULL,LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
    int code=libusb_init(&b->ctx);if(code<0){fail(env,libusb_error_name(code));goto error;}
    code=libusb_wrap_sys_device(b->ctx,b->descriptor,&b->device);if(code<0){fail(env,libusb_error_name(code));goto error;}
    b->listen=socket(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0);struct sockaddr_un address={.sun_family=AF_UNIX};strcpy(address.sun_path,b->path);unlink(b->path);
    if(b->listen<0||bind(b->listen,(struct sockaddr *)&address,sizeof(address))<0||chmod(b->path,0600)<0||listen(b->listen,1)<0){fail(env,"Cannot create private USB socket");goto error;}
    if(pthread_create(&b->thread,NULL,serve,b)!=0){fail(env,"Cannot start USB redirection");goto error;}return (jlong)(intptr_t)b;
error:
    if(name)(*env)->ReleaseStringUTFChars(env,path,name);if(b->device)libusb_close(b->device);if(b->ctx)libusb_exit(b->ctx);if(b->descriptor>=0)close(b->descriptor);if(b->listen>=0)close(b->listen);unlink(b->path);free(b);return 0;
}
JNIEXPORT void JNICALL Java_dev_mateuszsury_androidagent_UsbNative_stop(JNIEnv *env,jclass cls,jlong value){
    (void)env;(void)cls;Bridge *b=(Bridge *)(intptr_t)value;if(!b)return;atomic_store(&b->stopping,1);if(b->client>=0)shutdown(b->client,SHUT_RDWR);pthread_join(b->thread,NULL);
    if(b->client>=0)close(b->client);close(b->listen);libusb_exit(b->ctx);close(b->descriptor);unlink(b->path);free(b);
}
JNIEXPORT jstring JNICALL Java_dev_mateuszsury_androidagent_UsbNative_state(JNIEnv *env,jclass cls,jlong value){
    (void)cls;Bridge *b=(Bridge *)(intptr_t)value;return (*env)->NewStringUTF(env,!b?"stopped":atomic_load(&b->finished)?"disconnected":"active");
}
