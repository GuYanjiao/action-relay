//! Bounded protocol and transport core. No Android APIs, threads or timers.
pub mod ffi;
pub const MAX_FRAME: usize = 1023;
pub const STREAM_SIZE: usize = 1040;
const SLOT: usize = 18 + MAX_FRAME;
const CONTROLS: usize = 33; // 32 ordinary controls plus a preemptive stop.
pub const QUEUE_SIZE: usize = 8 + (CONTROLS + 1) * SLOT;

const fn table(poly: u32) -> [u32; 256] {
    let mut result = [0; 256];
    let mut n = 0;
    while n < 256 {
        let mut crc = n as u32;
        let mut bit = 0;
        while bit < 8 {
            crc = if crc & 1 != 0 { (crc >> 1) ^ poly } else { crc >> 1 };
            bit += 1;
        }
        result[n] = crc;
        n += 1;
    }
    result
}
const CRC16: [u32; 256] = table(0xa001);
const CRC32: [u32; 256] = table(0xedb88320);
pub fn crc(bytes: &[u8], wide: bool) -> u32 {
    let lookup = if wide { &CRC32 } else { &CRC16 };
    bytes.iter().fold(0x3aa3, |c, b| (c >> 8) ^ lookup[((c ^ *b as u32) & 255) as usize])
}
fn u16_at(b: &[u8], at: usize) -> usize { u16::from_le_bytes([b[at], b[at + 1]]) as usize }
fn u64_at(b: &[u8], at: usize) -> u64 { u64::from_le_bytes(b[at..at + 8].try_into().unwrap()) }
fn set16(b: &mut [u8], at: usize, value: usize) { b[at..at + 2].copy_from_slice(&(value as u16).to_le_bytes()); }
fn set64(b: &mut [u8], at: usize, value: u64) { b[at..at + 8].copy_from_slice(&value.to_le_bytes()); }

pub fn frame(set: u8, id: u8, kind: u8, seq: u16, payload: &[u8], out: &mut [u8]) -> Option<usize> {
    let len = 18usize.checked_add(payload.len())?;
    if len > MAX_FRAME || out.len() < len { return None; }
    out[..len].fill(0);
    out[0] = 0xaa; set16(out, 1, len); out[3] = kind; set16(out, 8, seq as usize);
    let header_crc = crc(&out[..10], false); set16(out, 10, header_crc as usize);
    out[12] = set; out[13] = id; out[14..len - 4].copy_from_slice(payload);
    let body_crc = crc(&out[..len - 4], true);
    out[len - 4..len].copy_from_slice(&body_crc.to_le_bytes());
    Some(len)
}
pub fn valid(bytes: &[u8]) -> bool {
    let len = bytes.len();
    (18..=MAX_FRAME).contains(&len) && bytes[0] == 0xaa && bytes[4] == 0
        && u16_at(bytes, 1) == len && u16_at(bytes, 10) == crc(&bytes[..10], false) as usize
        && u32::from_le_bytes(bytes[len - 4..].try_into().unwrap()) == crc(&bytes[..len - 4], true)
}

/// Output: cumulative discarded bytes (u64 LE), then u16-length-prefixed frames.
/// Direct-buffer state is owned by the JVM, so there are no native handles to leak.
pub fn feed(state: &mut [u8], input: &[u8], out: &mut [u8]) -> Option<usize> {
    if state.len() != STREAM_SIZE || input.len() > 8192 || out.len() < 8 { return None; }
    let pending = u16_at(state, 0);
    if pending > MAX_FRAME { return None; }
    let mut joined = Vec::with_capacity(pending + input.len());
    joined.extend_from_slice(&state[16..16 + pending]); joined.extend_from_slice(input);
    let mut dropped = u64_at(state, 8);
    let (mut at, mut written) = (0, 8);
    while at < joined.len() {
        let remaining = &joined[at..];
        if remaining[0] != 0xaa { at += 1; dropped = dropped.saturating_add(1); continue; }
        if remaining.len() < 12 { break; }
        let len = u16_at(remaining, 1);
        if !(18..=MAX_FRAME).contains(&len) || remaining[4] != 0 ||
            u16_at(remaining, 10) != crc(&remaining[..10], false) as usize {
            at += 1; dropped = dropped.saturating_add(1); continue;
        }
        if remaining.len() < len { break; }
        if !valid(&remaining[..len]) { at += 1; dropped = dropped.saturating_add(1); continue; }
        if written + 2 + len > out.len() { return None; }
        set16(out, written, len); written += 2;
        out[written..written + len].copy_from_slice(&remaining[..len]); written += len; at += len;
    }
    let tail = &joined[at..];
    if tail.len() > MAX_FRAME { return None; }
    state.fill(0); set16(state, 0, tail.len()); set64(state, 8, dropped);
    state[16..16 + tail.len()].copy_from_slice(tail); set64(out, 0, dropped);
    Some(written)
}

fn queue_meta(state: &[u8]) -> Option<(usize, usize)> {
    if state.len() != QUEUE_SIZE { return None; }
    let (head, count) = (u16_at(state, 0), u16_at(state, 2));
    if head >= CONTROLS || count > CONTROLS || state[4] > 1 { None } else { Some((head, count)) }
}
fn slot(index: usize) -> usize { 8 + index * SLOT }
fn recording(bytes: &[u8]) -> bool { bytes.len() >= 19 && bytes[12] == 0x1d && bytes[13] == 3 && bytes[18] <= 1 }
/// Negative results are rejected before transport writes. No automatic replay.
pub fn offer(state: &mut [u8], bytes: &[u8], now: u64, gps: bool) -> Result<(), &'static str> {
    let (mut head, mut count) = queue_meta(state).ok_or("state")?;
    if bytes.len() > MAX_FRAME { return Err("size"); }
    let deadline = now.checked_add(if gps { 1500 } else { 4000 }).filter(|v| *v <= i64::MAX as u64).ok_or("time")?;
    let index;
    if gps { index = CONTROLS; }
    else if recording(bytes) && bytes[18] == 1 {
        // Compact the ring in logical order; the stop removes queued start/stop
        // and supersedes GPS, without dropping unrelated control commands.
        let mut kept = 0;
        for n in 0..count {
            let src = slot((head + n) % CONTROLS);
            let len = u16_at(state, src);
            if len > MAX_FRAME { return Err("state"); }
            if !recording(&state[src + 18..src + 18 + len]) {
                let dst = slot((head + kept) % CONTROLS);
                state.copy_within(src..src + SLOT, dst); kept += 1;
            }
        }
        if kept >= CONTROLS { return Err("full"); }
        count = kept; head = (head + CONTROLS - 1) % CONTROLS; index = head; state[4] = 0;
    } else {
        if count >= 32 { return Err("full"); }
        index = (head + count) % CONTROLS;
    }
    let pos = slot(index); set16(state, pos, bytes.len());
    set64(state, pos + 2, now); set64(state, pos + 10, deadline);
    state[pos + 18..pos + 18 + bytes.len()].copy_from_slice(bytes);
    if gps { state[4] = 1; } else { set16(state, 0, head); set16(state, 2, count + 1); }
    Ok(())
}
/// Output: telemetry byte, queuedAt u64, deadline u64, frame. Expired control is
/// deliberately exposed to Android for a safe disconnect, never silently sent.
pub fn poll(state: &mut [u8], now: u64, out: &mut [u8]) -> Option<usize> {
    let (head, count) = queue_meta(state)?;
    if count == 0 && state[4] == 0 { return Some(0); }
    let gps = count == 0; let pos = slot(if gps { CONTROLS } else { head });
    let len = u16_at(state, pos);
    if len > MAX_FRAME || out.len() < len + 17 { return None; }
    if gps { state[4] = 0; }
    else { set16(state, 0, (head + 1) % CONTROLS); set16(state, 2, count - 1); }
    if gps && now >= u64_at(state, pos + 10) { return Some(0); }
    out[0] = u8::from(gps); out[1..17].copy_from_slice(&state[pos + 2..pos + 18]);
    out[17..17 + len].copy_from_slice(&state[pos + 18..pos + 18 + len]); Some(17 + len)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn packet(value: u8) -> Vec<u8> { let mut v = vec![0; 27]; v[12]=0x1d; v[13]=3; v[18]=value; v }
    #[test] fn split_noise_and_corruption() {
        let mut bytes=[0; MAX_FRAME]; let n=frame(0x1d,3,2,42,&[4,5,6],&mut bytes).unwrap();
        for split in 0..=n {
            let mut state=[0;STREAM_SIZE];let mut out=[0;12000];
            assert_eq!(feed(&mut state,&bytes[..split],&mut out).unwrap(),if split==n {n+10} else {8});
            let size=feed(&mut state,&bytes[split..n],&mut out).unwrap();
            if split<n {assert_eq!(size,n+10);assert_eq!(&out[10..size],&bytes[..n]);}
        }
        let mut state=[0;STREAM_SIZE];let mut out=[0;12000];let mut bad=bytes[..n].to_vec();bad[14]^=1;
        let joined=[vec![0x55,0,0xaa],bad,bytes[..n].to_vec()].concat();
        let size=feed(&mut state,&joined,&mut out).unwrap();assert_eq!(&out[10..size],&bytes[..n]);
        assert_eq!(u64_at(&out,0), (joined.len()-n) as u64);
        assert!(feed(&mut state,&[0;8193],&mut out).is_none());
    }
    #[test] fn queue_wrap_supersession_and_deadlines() {
        let mut q=vec![0;QUEUE_SIZE];let mut out=[0;1040];
        for _ in 0..80 {offer(&mut q,&[9],1,false).unwrap();assert_eq!(poll(&mut q,2,&mut out),Some(18));}
        for _ in 0..32 {offer(&mut q,&[9],100,false).unwrap();}
        assert!(offer(&mut q,&[9],100,false).is_err());
        offer(&mut q,&packet(1),101,false).unwrap();assert_eq!(u16_at(&q,2),33);
        assert_eq!(poll(&mut q,102,&mut out),Some(44));assert_eq!(out[35],1);
        q.fill(0);offer(&mut q,&packet(0),1,false).unwrap();offer(&mut q,&[1],1,true).unwrap();
        offer(&mut q,&packet(1),2,false).unwrap();assert_eq!(poll(&mut q,3,&mut out),Some(44));
        assert_eq!(poll(&mut q,3,&mut out),Some(0));
        offer(&mut q,&[1],0,true).unwrap();assert_eq!(poll(&mut q,1500,&mut out),Some(0));
        offer(&mut q,&[2],0,false).unwrap();assert_eq!(poll(&mut q,5000,&mut out),Some(18));assert_eq!(u64_at(&out,9),4000);
        assert!(offer(&mut q,&[],i64::MAX as u64,false).is_err());
    }
    #[test] fn bounded_noise_keeps_small_state() {
        let mut state=[0;STREAM_SIZE];let mut out=[0;12000];
        for i in 0..256u16 {
            let bytes:Vec<u8>=(0..8192).map(|n|((n as u16).wrapping_mul(37).wrapping_add(i)) as u8).collect();
            feed(&mut state,&bytes,&mut out).unwrap();assert!(u16_at(&state,0)<=MAX_FRAME);
        }
    }
}
