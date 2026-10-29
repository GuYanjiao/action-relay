#include <assert.h>
#include <string.h>
#include <openssl/evp.h>
#include <openssl/hmac.h>
#include "../miwear_auth.h"
#include "vectors.h"
static int mac(void *ctx,const uint8_t *k,size_t kl,const uint8_t *d,size_t dl,uint8_t out[32])
{ unsigned int n=0;(void)ctx;return HMAC(EVP_sha256(),k,(int)kl,d,dl,out,&n)&&n==32?0:-1; }
static int fail(void *ctx,const uint8_t *k,size_t kl,const uint8_t *d,size_t dl,uint8_t out[32])
{ (void)ctx;(void)k;(void)kl;(void)d;(void)dl;(void)out;return -1; }
int main(void)
{
    uint8_t key[16],phone[16],watch[16],proof[32];struct miwear_keys keys,zero={0};
    for(int i=0;i<16;i++){key[i]=(uint8_t)i;phone[i]=(uint8_t)(i+16);watch[i]=(uint8_t)(i+32);}
    assert(miwear_derive(mac,NULL,key,phone,watch,&keys)==0);
    assert(sizeof keys==40 && memcmp(&keys,gold_keys,40)==0);
    assert(miwear_verify_watch(mac,NULL,&keys,phone,watch,gold_watch)==0);
    assert(miwear_phone_proof(mac,NULL,&keys,phone,watch,proof)==0 && memcmp(proof,gold_phone,32)==0);
    for(int i=0;i<32;i++){memcpy(proof,gold_watch,32);proof[i]^=1;assert(miwear_verify_watch(mac,NULL,&keys,phone,watch,proof)!=0);}
    phone[0]^=1;assert(miwear_verify_watch(mac,NULL,&keys,phone,watch,gold_watch)!=0);phone[0]^=1;
    assert(miwear_derive(fail,NULL,key,phone,watch,&keys)!=0 && memcmp(&keys,&zero,40)==0);
    assert(miwear_phone_proof(fail,NULL,&keys,phone,watch,proof)!=0);
    for(int i=0;i<32;i++)assert(proof[i]==0);
    assert(miwear_derive(NULL,NULL,key,phone,watch,&keys)!=0);
    assert(miwear_verify_watch(mac,NULL,NULL,phone,watch,gold_watch)!=0);
    return 0;
}
