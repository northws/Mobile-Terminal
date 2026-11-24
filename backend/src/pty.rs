use std::env;
use std::borrow::Cow;
use std::fs::File;
use std::io;
use std::os::fd::{AsRawFd, FromRawFd, RawFd};
use std::os::unix::process::CommandExt;
use std::path::Path;
use std::process::{Child, Command, Stdio};

use warp_core::SessionId;
use warp_terminal::{bootstrap, shell::ShellType};
use warpui_core::AssetProvider;

struct BashAssets;

impl AssetProvider for BashAssets {
    fn get(&self, path: &str) -> anyhow::Result<Cow<'_, [u8]>> {
        let bytes: &[u8] = match path {
            "bundled/bootstrap/bash_init_shell.sh" => include_bytes!("../assets/bash_init_shell.sh"),
            "bundled/bootstrap/bash.sh" => include_bytes!("../assets/bash.sh"),
            "bundled/bootstrap/bash_body.sh" => include_bytes!("../assets/bash_body.sh"),
            _ => anyhow::bail!("unknown Bash bootstrap asset {path}"),
        };
        Ok(Cow::Borrowed(bytes))
    }
}

pub struct Pty {
    pub master: File,
    pub child: Child,
}

fn result(value: libc::c_int) -> io::Result<()> {
    if value == -1 { Err(io::Error::last_os_error()) } else { Ok(()) }
}

fn size(columns: u16, rows: u16) -> libc::winsize {
    libc::winsize { ws_col: columns, ws_row: rows, ws_xpixel: 0, ws_ypixel: 0 }
}

pub fn duplicate(fd: RawFd) -> io::Result<File> {
    let new_fd = unsafe { libc::fcntl(fd, libc::F_DUPFD_CLOEXEC, 3) };
    result(new_fd)?;
    Ok(unsafe { File::from_raw_fd(new_fd) })
}

impl Pty {
    pub fn spawn(home: &Path, session: SessionId, columns: u16, rows: u16) -> io::Result<Self> {
        let mut master = -1;
        let mut slave = -1;
        result(unsafe {
            libc::openpty(&mut master, &mut slave, std::ptr::null_mut(), std::ptr::null(), &size(columns, rows))
        })?;
        let master = unsafe { File::from_raw_fd(master) };
        let slave = unsafe { File::from_raw_fd(slave) };
        for file in [&master, &slave] {
            result(unsafe { libc::fcntl(file.as_raw_fd(), libc::F_SETFD, libc::FD_CLOEXEC) })?;
        }

        // Bash reads this path as its rcfile before displaying the first prompt.
        let mut startup = tempfile_file()?;
        use std::io::Write;
        let init = bootstrap::raw_init_shell_script_for_shell(ShellType::Bash, &BashAssets, session);
        writeln!(startup, "{init}")?;
        startup.write_all(&bootstrap::script_for_shell(ShellType::Bash, &BashAssets))?;
        use std::io::{Seek, SeekFrom};
        startup.seek(SeekFrom::Start(0))?;
        let startup_fd = startup.as_raw_fd();

        let mut command = Command::new("/bin/bash");
        command
            .arg("--noprofile")
            .arg("--rcfile").arg(format!("/proc/self/fd/{startup_fd}"))
            .arg("-i")
            .current_dir(home)
            .env("HOME", home)
            .env("TERM", "xterm-256color")
            .env("COLORTERM", "truecolor")
            .env("WARP_HONOR_PS1", "1")
            .env("WARP_PROMPT_NODE_VERSION_ENABLED", "0")
            .stdin(Stdio::from(slave.try_clone()?))
            .stdout(Stdio::from(slave.try_clone()?))
            .stderr(Stdio::from(slave));
        unsafe {
            command.pre_exec(move || {
                result(libc::setsid())?;
                result(libc::ioctl(0, libc::TIOCSCTTY, 0))?;
                result(libc::fcntl(startup_fd, libc::F_SETFD, 0))?;
                Ok(())
            });
        }
        let child = command.spawn()?;
        Ok(Self { master, child })
    }

    pub fn resize(&self, columns: u16, rows: u16) -> io::Result<()> {
        result(unsafe { libc::ioctl(self.master.as_raw_fd(), libc::TIOCSWINSZ, &size(columns, rows)) })
    }

    pub fn hangup(&mut self) {
        let foreground = unsafe { libc::tcgetpgrp(self.master.as_raw_fd()) };
        let shell = self.child.id() as i32;
        unsafe {
            if foreground > 0 && foreground != shell {
                libc::kill(-foreground, libc::SIGHUP);
            }
            libc::kill(-shell, libc::SIGHUP);
        }
    }
}

fn tempfile_file() -> io::Result<File> {
    let mut path = env::temp_dir().into_os_string().into_encoded_bytes();
    path.extend_from_slice(b"/warp-rc.XXXXXX\0");
    let fd = unsafe { libc::mkostemp(path.as_mut_ptr().cast(), libc::O_CLOEXEC) };
    result(fd)?;
    let file = unsafe { File::from_raw_fd(fd) };
    result(unsafe { libc::unlink(path.as_ptr().cast()) })?;
    Ok(file)
}
