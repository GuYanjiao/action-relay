/* Independent expression of HKDF-SHA256 with the MiWear "miwear-auth" info label.
 * Protocol evidence: AstroBox Core auth.rs dc08a079501b3ca7dd9215727ccbc348278914fc.
 * This computes proofs only; it does not authenticate a BLE connection by itself. */
#include <string.h>
#include "miwear_auth.h"
static void wipe(void *ptr,size_t n)
{ volatile uint8_t *p=ptr;while(n--)*p++=0; }
int miwear_derive(miwear_hmac_fn hmac,void *ctx,const uint8_t key[16],
                  const uint8_t phone[16],const uint8_t watch[16],struct miwear_keys *out)
{
    if(!out)return -1;
    memset(out,0,sizeof *out);
    if(!hmac||!key||!phone||!watch)return -1;
    static const uint8_t info[]="miwear-auth";
    uint8_t salt[32],prk[32],expand[32+sizeof(info)],block[64];
    memcpy(salt,phone,16);memcpy(salt+16,watch,16);
    int error=hmac(ctx,salt,sizeof salt,key,16,prk);
    if(!error){
        memcpy(expand,info,sizeof(info)-1);expand[sizeof(info)-1]=1;
        error=hmac(ctx,prk,32,expand,sizeof(info),block);
    }
    if(!error){
        memcpy(expand,block,32);memcpy(expand+32,info,sizeof(info)-1);
        expand[32+sizeof(info)-1]=2;
        error=hmac(ctx,prk,32,expand,sizeof expand,block+32);
    }
    if(!error){
        memcpy(out->rx_key,block,16);memcpy(out->tx_key,block+16,16);
        memcpy(out->rx_nonce,block+32,4);memcpy(out->tx_nonce,block+36,4);
    }
    wipe(salt,sizeof salt);wipe(prk,sizeof prk);wipe(expand,sizeof expand);wipe(block,sizeof block);
    return error? -1:0;
}
int miwear_verify_watch(miwear_hmac_fn hmac,void *ctx,const struct miwear_keys *keys,
                        const uint8_t phone[16],const uint8_t watch[16],const uint8_t signature[32])
{
    if(!hmac||!keys||!phone||!watch||!signature)return -1;
    uint8_t input[32],expected[32];memcpy(input,watch,16);memcpy(input+16,phone,16);
    int error=hmac(ctx,keys->rx_key,16,input,32,expected);
    uint8_t difference=0;
    if(!error)for(size_t i=0;i<32;i++)difference|=expected[i]^signature[i];
    wipe(input,sizeof input);wipe(expected,sizeof expected);
    return error||difference? -1:0;
}
int miwear_phone_proof(miwear_hmac_fn hmac,void *ctx,const struct miwear_keys *keys,
                       const uint8_t phone[16],const uint8_t watch[16],uint8_t signature[32])
{
    if(!signature)return -1;
    memset(signature,0,32);
    if(!hmac||!keys||!phone||!watch)return -1;
    uint8_t input[32];memcpy(input,phone,16);memcpy(input+16,watch,16);
    int error=hmac(ctx,keys->tx_key,16,input,32,signature);
    wipe(input,sizeof input);
    if(error)wipe(signature,32);
    return error? -1:0;
}
