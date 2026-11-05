//! JNI shim owns these buffers for the entire synchronous call. It verifies
//! capacities and prevents Java mutations while a mutable Rust slice is alive.
use crate::*;
use std::slice;

/// # Safety
/// p must reference n readable bytes for this call, or be null only when n=0.
unsafe fn input<'a>(p: *const u8, n: usize) -> Option<&'a [u8]> {
    if n == 0 { Some(&[]) } else if p.is_null() || n > 8192 { None }
    else { Some(unsafe { slice::from_raw_parts(p, n) }) }
}
/// # Safety
/// p must exclusively reference n writable bytes for this call.
unsafe fn output<'a>(p: *mut u8, n: usize) -> Option<&'a mut [u8]> {
    if p.is_null() || n > 65536 { None } else { Some(unsafe { slice::from_raw_parts_mut(p, n) }) }
}
#[unsafe(no_mangle)]
pub extern "C" fn relay_stream_size() -> usize { STREAM_SIZE }
#[unsafe(no_mangle)]
pub extern "C" fn relay_queue_size() -> usize { QUEUE_SIZE }

/// # Safety
/// data must reference len readable bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn relay_crc(data: *const u8, len: usize, wide: i32) -> u32 {
    unsafe { input(data, len) }.map(|b| crc(b, wide != 0)).unwrap_or(0)
}
/// # Safety
/// data must reference len readable bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn relay_valid(data: *const u8, len: usize) -> i32 {
    i32::from(unsafe { input(data, len) }.is_some_and(valid))
}
/// # Safety
/// Input/output pointers must be valid, nonoverlapping buffers of the supplied lengths.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn relay_frame(set: u8, id: u8, kind: u8, seq: u16,
    data: *const u8, len: usize, out: *mut u8, cap: usize) -> isize {
    let (Some(bytes), Some(dest)) = (unsafe { input(data,len) }, unsafe { output(out,cap) }) else { return -1; };
    frame(set,id,kind,seq,bytes,dest).map(|n| n as isize).unwrap_or(-1)
}
/// # Safety
/// State, input and output must be valid nonoverlapping buffers; state is exclusive.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn relay_feed(state: *mut u8, size: usize, data: *const u8,
    len: usize, out: *mut u8, cap: usize) -> isize {
    let (Some(state),Some(bytes),Some(dest)) = (unsafe { output(state,size) },unsafe { input(data,len) },unsafe { output(out,cap) }) else {return -1;};
    feed(state,bytes,dest).map(|n| n as isize).unwrap_or(-1)
}
/// # Safety
/// State must be an exclusive QUEUE_SIZE buffer; data references len readable bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn relay_offer(state: *mut u8,size: usize,data: *const u8,len: usize,now: i64,gps: i32) -> i32 {
    let (Some(state),Some(bytes))=(unsafe {output(state,size)},unsafe {input(data,len)}) else {return -1;};
    if now < 0 {return -1;}
    match offer(state,bytes,now as u64,gps!=0) {Ok(())=>0,Err("full")=>-2,Err(_)=>-1}
}
/// # Safety
/// State/output are valid, exclusive, nonoverlapping buffers of the supplied lengths.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn relay_poll(state: *mut u8,size: usize,now:i64,out:*mut u8,cap:usize) -> isize {
    let (Some(state),Some(dest))=(unsafe {output(state,size)},unsafe {output(out,cap)}) else {return -1;};
    if now<0 {return -1;}
    poll(state,now as u64,dest).map(|n|n as isize).unwrap_or(-1)
}
/// # Safety
/// State must reference size readable bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn relay_count(state:*const u8,size:usize)->i32 {
    if size!=QUEUE_SIZE || state.is_null() {return -1;}
    queue_meta(unsafe {slice::from_raw_parts(state,size)}).map(|(_,n)|n as i32).unwrap_or(-1)
}
/// # Safety
/// State must exclusively reference size writable bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn relay_clear(state:*mut u8,size:usize,gps_only:i32)->i32 {
    let Some(state)=(unsafe {output(state,size)}) else {return -1;};
    if gps_only!=0 {
        if queue_meta(state).is_none() {return -1;}
        state[4]=0;state[slot(CONTROLS)..].fill(0);
    } else if size==STREAM_SIZE || size==QUEUE_SIZE {state.fill(0);} else {return -1;}
    0
}
