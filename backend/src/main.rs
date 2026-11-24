mod protocol;
mod pty;
mod terminal;

use std::env;
use std::fs::File;
use std::io::{self, Read, Write};
use std::os::fd::AsRawFd;
use std::os::unix::process::ExitStatusExt;
use std::path::PathBuf;
use std::time::{Duration, Instant};

use serde::Deserialize;
use serde_json::json;
use warp_core::SessionId;
use warp_terminal::model::ansi::Processor;

use crate::pty::Pty;
use crate::terminal::Terminal;

#[derive(Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
enum Request {
    Resize { columns: u16, rows: u16 },
    Close,
}

fn main() {
    if let Err(error) = run() {
        eprintln!("Warp terminal backend: {error}");
        std::process::exit(1);
    }
}

fn run() -> io::Result<()> {
    // The standalone backend keeps Warp's optional terminal features at their defaults.
    warp_core::features::mark_initialized();
    let mut random = [0; 8];
    File::open("/dev/urandom")?.read_exact(&mut random)?;
    let session = SessionId::from((u64::from_ne_bytes(random) >> 12) + 1);
    let home = env::var_os("HOME").map(PathBuf::from)
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "HOME is missing"))?;
    let mut terminal = Terminal::new(session);
    let mut parser = Processor::new();
    let mut input = pty::duplicate(libc::STDIN_FILENO)?;
    let mut output = io::BufWriter::new(io::stdout().lock());
    protocol::hello(&mut output)?;
    let mut pty = Pty::spawn(&home, session, 80, 24)?;
    let status = relay(&mut input, &mut output, &mut pty, &mut parser, &mut terminal);
    if status.is_err() {
        pty.hangup();
        let _ = pty.child.kill();
        let _ = pty.child.wait();
    }
    protocol::event(&mut output, json!({"type":"exit", "code":status?}))
}

fn relay(
    input: &mut File,
    output: &mut impl Write,
    pty: &mut Pty,
    parser: &mut Processor,
    terminal: &mut Terminal,
) -> io::Result<i32> {
    let mut deadline = None;
    let mut bytes = [0; 8192];
    let mut fds = [
        libc::pollfd { fd: input.as_raw_fd(), events: libc::POLLIN, revents: 0 },
        libc::pollfd { fd: pty.master.as_raw_fd(), events: libc::POLLIN, revents: 0 },
    ];
    loop {
        if unsafe { libc::poll(fds.as_mut_ptr(), 2, 45) } < 0 {
            let error = io::Error::last_os_error();
            if error.kind() == io::ErrorKind::Interrupted { continue; }
            return Err(error);
        }
        if fds[1].revents != 0 {
            match pty.master.read(&mut bytes) {
                Ok(0) => break,
                Ok(length) => {
                    let chunk = &bytes[..length];
                    protocol::write(output, protocol::TERMINAL, chunk)?;
                    // The Android terminal emulator handles terminal replies.
                    parser.parse_bytes(terminal, chunk, &mut io::sink());
                }
                Err(error) if error.raw_os_error() == Some(libc::EIO) => break,
                Err(error) => return Err(error),
            }
        }
        if parser.sync_output_remaining_timeout().is_some_and(|timeout| timeout.is_zero()) {
            parser.finish_sync_output(terminal, &mut io::sink());
        }
        terminal.send_events(output)?;
        if fds[0].revents != 0 {
            let close = match protocol::read(input)? {
                Some((protocol::TERMINAL, bytes)) => {
                    pty.master.write_all(&bytes)?;
                    false
                }
                Some((protocol::JSON, bytes)) => match serde_json::from_slice(&bytes)? {
                    Request::Resize { columns, rows } => {
                        pty.resize(columns.clamp(4, 500), rows.clamp(4, 250))?;
                        false
                    }
                    Request::Close => true,
                },
                Some(_) => return Err(io::Error::new(io::ErrorKind::InvalidData, "unknown frame kind")),
                None => true,
            };
            // A closed input stream or explicit Close ends the shell.
            if close {
                fds[0].fd = -1;
                pty.hangup();
                deadline = Some(Instant::now() + Duration::from_millis(800));
            }
        }
        if fds[1].revents == 0 && let Some(status) = pty.child.try_wait()? {
            return Ok(status.code().unwrap_or_else(|| 128 + status.signal().unwrap_or(0)));
        }
        if deadline.is_some_and(|end| Instant::now() >= end) {
            let _ = pty.child.kill();
            break;
        }
    }
    parser.finish_sync_output(terminal, &mut io::sink());
    terminal.send_events(output)?;
    pty.hangup();
    let status = pty.child.wait()?;
    Ok(status.code().unwrap_or_else(|| 128 + status.signal().unwrap_or(0)))
}
