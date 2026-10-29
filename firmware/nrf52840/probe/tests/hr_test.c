#include <assert.h>
#include "../src/hr.h"
int main(void)
{
    uint16_t bpm=999;
    const uint8_t normal[]={0,72}, wide[]={1,0x2c,1}, optional[]={0x16,101,0,0};
    assert(hr_decode(normal,sizeof normal,&bpm) && bpm==72);
    assert(hr_decode(wide,sizeof wide,&bpm) && bpm==300);
    assert(hr_decode(optional,sizeof optional,&bpm) && bpm==101);
    assert(!hr_decode(wide,2,&bpm));
    assert(!hr_decode(normal,1,&bpm));
    assert(!hr_decode(NULL,2,&bpm));
    assert(!hr_decode(normal,2,NULL));
    return 0;
}
