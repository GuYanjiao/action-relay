/* Action Relay BLE diagnostics. No pairing, auth key, camera control or auto-connect. */
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <zephyr/kernel.h>
#include <zephyr/shell/shell.h>
#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/hci.h>
#include <zephyr/bluetooth/conn.h>
#include <zephyr/bluetooth/gatt.h>
#include <zephyr/bluetooth/uuid.h>
#include <zephyr/sys/byteorder.h>
#include "hr.h"

static struct bt_conn *peer;
static struct bt_gatt_discover_params discovery;
static struct bt_gatt_subscribe_params subscription;
static bool discovery_busy, scanning;
static uint16_t hr_handle, hr_end;

struct advert { char name[40]; bool hr, xiaomi; };
static bool ad_field(struct bt_data *data, void *user)
{
    struct advert *ad=user;
    if (data->type==BT_DATA_NAME_COMPLETE || data->type==BT_DATA_NAME_SHORTENED) {
        size_t n=MIN(data->data_len,sizeof(ad->name)-1);
        memcpy(ad->name,data->data,n); ad->name[n]=0;
    }
    if (data->type==BT_DATA_UUID16_ALL || data->type==BT_DATA_UUID16_SOME) {
        for (size_t i=0;i+1<data->data_len;i+=2) {
            uint16_t uuid=sys_get_le16(data->data+i);
            if (uuid==0x180d) ad->hr=true;
            if (uuid==0xfe95) ad->xiaomi=true;
        }
    }
    return true;
}
static void scanned(const bt_addr_le_t *addr,int8_t rssi,uint8_t type,struct net_buf_simple *buf)
{
    struct advert ad={0}; bt_data_parse(buf,ad_field,&ad);
    if (!ad.hr && !ad.xiaomi && !strstr(ad.name,"Watch S4")) return;
    char address[BT_ADDR_LE_STR_LEN]; bt_addr_le_to_str(addr,address,sizeof address);
    printk("ADV %s RSSI=%d name=%s HR=%d FE95=%d type=%u\n",address,rssi,ad.name,ad.hr,ad.xiaomi,type);
}
static void connected(struct bt_conn *conn,uint8_t error)
{
    if (error) {
        printk("CONNECT failed=0x%02x\n",error);
        if (peer) {bt_conn_unref(peer);peer=NULL;}
        return;
    }
    printk("CONNECTED; run probe services, then probe chars\n");
}
static void disconnected(struct bt_conn *conn,uint8_t reason)
{
    printk("DISCONNECTED reason=0x%02x\n",reason);
    if (peer==conn) {bt_conn_unref(peer);peer=NULL;}
    discovery_busy=false;hr_handle=hr_end=0;
    memset(&discovery,0,sizeof discovery);
    memset(&subscription,0,sizeof subscription);
}
BT_CONN_CB_DEFINE(connection_callbacks)={.connected=connected,.disconnected=disconnected};

static uint8_t hr_notify(struct bt_conn *conn,struct bt_gatt_subscribe_params *params,
                         const void *data,uint16_t length)
{
    if (!data) {printk("HR unsubscribed\n");params->value_handle=0;return BT_GATT_ITER_STOP;}
    uint16_t bpm;
    if (hr_decode(data,length,&bpm))printk("HR bpm=%u\n",bpm);
    else printk("HR invalid length=%u\n",length);
    return BT_GATT_ITER_CONTINUE;
}
static uint8_t found(struct bt_conn *conn,const struct bt_gatt_attr *attr,
                     struct bt_gatt_discover_params *params)
{
    if (!attr) {discovery_busy=false;printk("DISCOVERY done\n");return BT_GATT_ITER_STOP;}
    char text[BT_UUID_STR_LEN];
    if (params->type==BT_GATT_DISCOVER_PRIMARY) {
        const struct bt_gatt_service_val *service=attr->user_data;
        bt_uuid_to_str(service->uuid,text,sizeof text);
        printk("SERVICE %s start=0x%04x end=0x%04x\n",text,attr->handle,service->end_handle);
    } else if (params->type==BT_GATT_DISCOVER_CHARACTERISTIC) {
        const struct bt_gatt_chrc *ch=attr->user_data;
        if (hr_handle && hr_end==0xffff && attr->handle>hr_handle)hr_end=attr->handle-1;
        bt_uuid_to_str(ch->uuid,text,sizeof text);
        printk("CHAR %s declaration=0x%04x value=0x%04x properties=0x%02x\n",
               text,attr->handle,ch->value_handle,ch->properties);
        if (!bt_uuid_cmp(ch->uuid,BT_UUID_HRS_MEASUREMENT) && (ch->properties&BT_GATT_CHRC_NOTIFY)) {
            hr_handle=ch->value_handle;hr_end=0xffff;
        }
    } else if (params->type==BT_GATT_DISCOVER_DESCRIPTOR) {
        subscription.notify=hr_notify;subscription.value=BT_GATT_CCC_NOTIFY;
        subscription.value_handle=hr_handle;subscription.ccc_handle=attr->handle;
        int error=bt_gatt_subscribe(conn,&subscription);
        printk("HR subscribe result=%d ccc=0x%04x\n",error,attr->handle);
        if (error)memset(&subscription,0,sizeof subscription);
        discovery_busy=false;
        return BT_GATT_ITER_STOP;
    }
    return BT_GATT_ITER_CONTINUE;
}
static int discover(uint8_t type,const struct bt_uuid *uuid,uint16_t start,uint16_t end)
{
    if (!peer)return -ENOTCONN;
    if (discovery_busy)return -EBUSY;
    memset(&discovery,0,sizeof discovery);
    discovery.type=type;discovery.uuid=uuid;discovery.func=found;
    discovery.start_handle=start;discovery.end_handle=end;discovery_busy=true;
    int error=bt_gatt_discover(peer,&discovery);
    if(error)discovery_busy=false;
    return error;
}
static int cmd_scan(const struct shell *sh,size_t argc,char **argv)
{
    int error=bt_le_scan_start(BT_LE_SCAN_ACTIVE,scanned);
    if (!error)scanning=true;
    shell_print(sh,"scan=%d; only S4/HR/FE95 advertisements are printed",error);return error;
}
static int cmd_stop(const struct shell *sh,size_t argc,char **argv)
{
    int error=bt_le_scan_stop();if (!error)scanning=false;
    shell_print(sh,"scan stop=%d",error);return error;
}
static int cmd_connect(const struct shell *sh,size_t argc,char **argv)
{
    if(peer)return -EALREADY;
    bt_addr_le_t address;
    int error=bt_addr_from_str(argv[1],&address.a);
    if (!strcmp(argv[2],"public"))address.type=BT_ADDR_LE_PUBLIC;
    else if (!strcmp(argv[2],"random"))address.type=BT_ADDR_LE_RANDOM;
    else error=-EINVAL;
    if(error){shell_error(sh,"use: probe connect AA:BB:CC:DD:EE:FF public|random");return error;}
    if(scanning){error=bt_le_scan_stop();if(error)return error;scanning=false;}
    error=bt_conn_le_create(&address,BT_CONN_LE_CREATE_CONN,BT_LE_CONN_PARAM_DEFAULT,&peer);
    shell_print(sh,"connection requested=%d; no automatic pairing or reconnect",error);return error;
}
static int cmd_services(const struct shell *sh,size_t argc,char **argv)
{ return discover(BT_GATT_DISCOVER_PRIMARY,NULL,1,0xffff); }
static int cmd_chars(const struct shell *sh,size_t argc,char **argv)
{
    if(discovery_busy || subscription.value_handle)return -EBUSY;
    hr_handle=0;hr_end=0xffff;
    return discover(BT_GATT_DISCOVER_CHARACTERISTIC,NULL,1,0xffff);
}
static int cmd_hr(const struct shell *sh,size_t argc,char **argv)
{
    if(!hr_handle){shell_error(sh,"run probe chars first; HR notify characteristic required");return -ENOENT;}
    if(subscription.value_handle)return -EALREADY;
    return discover(BT_GATT_DISCOVER_DESCRIPTOR,BT_UUID_GATT_CCC,hr_handle+1,hr_end);
}
static int cmd_disconnect(const struct shell *sh,size_t argc,char **argv)
{ return peer ? bt_conn_disconnect(peer,BT_HCI_ERR_REMOTE_USER_TERM_CONN) : -ENOTCONN; }
SHELL_STATIC_SUBCMD_SET_CREATE(probe_commands,
    SHELL_CMD(scan,NULL,"Scan S4, heart-rate and Xiaomi advertisements",cmd_scan),
    SHELL_CMD(stop,NULL,"Stop scanning",cmd_stop),
    SHELL_CMD_ARG(connect,NULL,"Connect to explicit address and public/random type",cmd_connect,3,0),
    SHELL_CMD(services,NULL,"List all primary service UUIDs",cmd_services),
    SHELL_CMD(chars,NULL,"List characteristic UUIDs and properties",cmd_chars),
    SHELL_CMD(hr,NULL,"Subscribe to standard HR measurement",cmd_hr),
    SHELL_CMD(disconnect,NULL,"Disconnect without changing bonds",cmd_disconnect),
    SHELL_SUBCMD_SET_END);
SHELL_CMD_REGISTER(probe,&probe_commands,"Action Relay S4 BLE diagnostics",NULL);
int main(void)
{
    int error=bt_enable(NULL);
    printk("Action Relay S4 probe phase 1; bt_enable=%d\n",error);
    printk("No automatic connection, bonding, authentication or camera command. Type probe scan.\n");
    return error;
}
