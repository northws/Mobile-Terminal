use std::io::{self, Read, Write};

use serde_json::{Value, json};

pub const VERSION: u32 = 2;
pub const JSON: u8 = 0;
pub const TERMINAL: u8 = 1;
const MAX_FRAME: usize = 256 * 1024;

pub fn read(reader: &mut impl Read) -> io::Result<Option<(u8, Vec<u8>)>> {
    let mut header = [0; 4];
    if reader.read(&mut header[..1])? == 0 {
        return Ok(None);
    }
    reader.read_exact(&mut header[1..])?;
    let size = u32::from_be_bytes(header) as usize;
    if !(1..=MAX_FRAME).contains(&size) {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid frame size"));
    }
    let mut kind = [0];
    reader.read_exact(&mut kind)?;
    let mut body = vec![0; size - 1];
    reader.read_exact(&mut body)?;
    Ok(Some((kind[0], body)))
}

pub fn write(writer: &mut impl Write, kind: u8, body: &[u8]) -> io::Result<()> {
    if body.len() >= MAX_FRAME {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "frame too large"));
    }
    writer.write_all(&((body.len() + 1) as u32).to_be_bytes())?;
    writer.write_all(&[kind])?;
    writer.write_all(body)?;
    writer.flush()
}

pub fn event(writer: &mut impl Write, payload: Value) -> io::Result<()> {
    write(writer, JSON, &serde_json::to_vec(&payload)?)
}

pub fn hello(writer: &mut impl Write) -> io::Result<()> {
    event(writer, json!({"type": "hello", "version": VERSION}))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_consecutive_frames_one_byte_at_a_time() {
        struct Fragmented<R>(R);
        impl<R: Read> Read for Fragmented<R> {
            fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
                let limit = buf.len().min(1);
                self.0.read(&mut buf[..limit])
            }
        }
        let mut bytes = Vec::new();
        write(&mut bytes, TERMINAL, "你好".as_bytes()).unwrap();
        hello(&mut bytes).unwrap();
        let mut reader = Fragmented(bytes.as_slice());
        assert_eq!(read(&mut reader).unwrap(), Some((TERMINAL, "你好".as_bytes().to_vec())));
        assert_eq!(read(&mut reader).unwrap().unwrap().0, JSON);
        assert_eq!(read(&mut reader).unwrap(), None);
    }

    #[test]
    fn rejects_empty_oversized_and_truncated_frames() {
        for bytes in [&[0, 0, 0, 0][..], &[0, 4, 0, 1], &[0, 0, 0, 3, 1, 1]] {
            assert!(read(&mut &*bytes).is_err());
        }
    }
}
