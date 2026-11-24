use std::io::{self, Write};

use serde_json::{Value, json};
use warp_terminal::model::{
    KeyboardModes, KeyboardModesApplyBehavior,
    ansi::*,
    index::VisibleRow,
    selection::ScrollDelta,
    session::SessionId,
};
use warpui_core::color::ColorU;

use crate::protocol;

#[derive(Default)]
struct StatusLine {
    row: Option<usize>,
    writing: bool,
    saved_cursor: bool,
    text: String,
    published: String,
}

impl StatusLine {
    fn reset(&mut self) {
        self.row = None;
        self.writing = false;
        self.saved_cursor = false;
        self.text.clear();
    }
}

pub struct Terminal {
    session: SessionId,
    rows: usize,
    active: bool,
    alternate: bool,
    text: String,
    status: StatusLine,
    prompt_directory: Option<String>,
    foreground: Option<u32>,
    bold: bool,
    events: Vec<Value>,
}

impl Terminal {
    pub fn new(session: SessionId, rows: u16) -> Self {
        Self {
            session, rows: usize::from(rows), active: false, alternate: false, text: String::new(),
            status: StatusLine::default(), prompt_directory: None,
            foreground: None, bold: false, events: Vec::new(),
        }
    }

    pub fn resize(&mut self, rows: u16) {
        self.rows = usize::from(rows);
    }

    fn flush_text(&mut self) {
        if !self.text.is_empty() {
            self.events.push(json!({
                "type":"output", "text": std::mem::take(&mut self.text),
                "foreground":self.foreground, "bold":self.bold,
            }));
        }
    }

    fn line_edit(&mut self, operation: &str, column: Option<usize>) {
        if self.active && !self.alternate && !self.status.writing {
            self.flush_text();
            self.events.push(json!({"type":"line_edit", "operation":operation, "column":column}));
        }
    }

    fn finish(&mut self, code: i32) {
        self.flush_text();
        self.status.reset();
        if self.active {
            self.events.push(json!({"type":"finished", "code":code}));
            self.active = false;
        }
    }

    fn prompt(&mut self, data: PromptMetadata) {
        if !data.was_sent_after_in_band_command() {
            self.prompt_directory = data.pwd;
        }
    }

    pub fn send_events(&mut self, writer: &mut impl Write) -> io::Result<()> {
        self.flush_text();
        let status = self.status.text.trim_end();
        if status != self.status.published {
            self.status.published = status.to_owned();
            let text = (!status.is_empty()).then_some(status);
            self.events.push(json!({"type":"status_line", "text":text}));
        }
        for event in self.events.drain(..) {
            protocol::event(writer, event)?;
        }
        Ok(())
    }
}

impl Handler for Terminal {
    fn is_registered_session(&self, session: SessionId) -> bool {
        session == self.session
    }

    fn preexec(&mut self, data: PreexecValue) {
        self.flush_text();
        self.status.reset();
        self.active = true;
        self.events.push(json!({"type":"started", "command":data.command}));
    }

    fn command_finished(&mut self, data: CommandFinishedValue) {
        self.finish(data.completion_metadata.exit_code.value());
    }

    fn precmd_with_completion_metadata(&mut self, data: PrecmdValue) {
        self.finish(data.completion_metadata.exit_code.value());
        self.prompt(data.prompt_metadata);
    }

    fn prompt_only_precmd(&mut self, data: PromptMetadata) {
        self.prompt(data);
    }

    fn prompt_marker(&mut self, marker: PromptMarker) {
        if matches!(marker, PromptMarker::EndPrompt)
            && let Some(directory) = self.prompt_directory.take()
        {
            self.events.push(json!({"type":"prompt", "directory":directory}));
        }
    }

    fn input(&mut self, character: char) {
        if self.active && !self.alternate {
            if self.status.writing {
                if self.status.text.len() < 4096 {
                    self.status.text.push(character);
                }
            } else {
                self.text.push(character);
            }
        }
    }

    fn put_tab(&mut self, count: u16) {
        for _ in 0..count { self.input('\t'); }
    }

    fn backspace(&mut self) {
        self.line_edit("backspace", None);
    }

    fn linefeed(&mut self) -> ScrollDelta {
        if !self.status.writing {
            self.input('\n');
        }
        ScrollDelta::zero()
    }

    fn newline(&mut self) {
        self.linefeed();
    }

    fn set_mode(&mut self, mode: Mode) {
        if matches!(mode, Mode::SwapScreen { .. }) && !self.alternate {
            self.flush_text();
            self.status.reset();
            self.alternate = true;
            self.events.push(json!({"type":"screen", "alternate":true}));
        }
    }

    fn unset_mode(&mut self, mode: Mode) {
        if matches!(mode, Mode::SwapScreen { .. }) && self.alternate {
            self.alternate = false;
            self.events.push(json!({"type":"screen", "alternate":false}));
        }
    }

    fn terminal_attribute(&mut self, attr: Attr) {
        match attr {
            Attr::Reset => {
                self.flush_text();
                self.foreground = None;
                self.bold = false;
            }
            Attr::Bold | Attr::CancelBold | Attr::CancelBoldDim => {
                self.flush_text();
                self.bold = attr == Attr::Bold;
            }
            Attr::Foreground(color) => {
                self.flush_text();
                self.foreground = match color {
                    Color::Named(name) => palette(name.into_color_index()),
                    Color::Indexed(index) => palette(index as usize),
                    Color::Spec(rgb) => Some(u32::from_be_bytes([0, rgb.r, rgb.g, rgb.b])),
                };
            }
            _ => {}
        }
    }

    // The Android VT renderer handles general cursor operations and device replies.
    fn set_title(&mut self, _: Option<String>) {}
    fn set_cursor_style(&mut self, _: Option<CursorStyle>) {}
    fn set_cursor_shape(&mut self, _shape: CursorShape) {}
    fn goto(&mut self, row: VisibleRow, column: usize) {
        self.status.writing = self.status.row == Some(row.0);
        if self.status.writing && column == 0 {
            self.status.text.clear();
        }
    }
    fn goto_line(&mut self, _: VisibleRow) {}
    fn goto_col(&mut self, column: usize) { self.line_edit("column", Some(column)); }
    fn insert_blank(&mut self, _: usize) {}
    fn move_up(&mut self, _: usize) {}
    fn move_down(&mut self, _: usize) {}
    fn identify_terminal<W: io::Write>(&mut self, _: &mut W, _intermediate: Option<char>) {}
    fn report_xtversion<W: io::Write>(&mut self, _: &mut W) {}
    fn device_status<W: io::Write>(&mut self, _: &mut W, _: usize) {}
    fn move_forward(&mut self, _: usize) {}
    fn move_backward(&mut self, _: usize) {}
    fn move_down_and_cr(&mut self, _: usize) {}
    fn move_up_and_cr(&mut self, _: usize) {}
    fn carriage_return(&mut self) { self.line_edit("column", Some(0)); }
    fn bell(&mut self) {}
    fn substitute(&mut self) {}
    fn set_horizontal_tabstop(&mut self) {}
    fn scroll_up(&mut self, _: usize) -> ScrollDelta { ScrollDelta::zero() }
    fn scroll_down(&mut self, _: usize) -> ScrollDelta { ScrollDelta::zero() }
    fn insert_blank_lines(&mut self, _: usize) -> ScrollDelta { ScrollDelta::zero() }
    fn delete_lines(&mut self, _: usize) -> ScrollDelta { ScrollDelta::zero() }
    fn erase_chars(&mut self, _: usize) {}
    fn delete_chars(&mut self, _: usize) {}
    fn move_backward_tabs(&mut self, _count: u16) {}
    fn move_forward_tabs(&mut self, _count: u16) {}
    fn save_cursor_position(&mut self) { self.status.saved_cursor = self.status.writing; }
    fn restore_cursor_position(&mut self) { self.status.writing = self.status.saved_cursor; }
    fn clear_line(&mut self, mode: LineClearMode) {
        let operation = match mode {
            LineClearMode::Right => "clear_right",
            LineClearMode::Left => "clear_left",
            LineClearMode::All => "clear_all",
        };
        self.line_edit(operation, None);
    }
    fn clear_screen(&mut self, _mode: ClearMode) {}
    fn clear_tabs(&mut self, _mode: TabulationClearMode) {}
    fn reset_state(&mut self) {}
    fn reverse_index(&mut self) -> ScrollDelta { ScrollDelta::zero() }
    fn set_keyboard_enhancement_flags(
        &mut self,
        _mode: KeyboardModes,
        _apply: KeyboardModesApplyBehavior,
    ) {}
    fn push_keyboard_enhancement_flags(&mut self, _mode: KeyboardModes) {}
    fn pop_keyboard_enhancement_flags(&mut self, _count: u16) {}
    fn query_keyboard_enhancement_flags<W: io::Write>(&mut self, _writer: &mut W) {}
    fn set_scrolling_region(&mut self, top: usize, bottom: Option<usize>) {
        if self.active && !self.alternate {
            // APT reserves exactly the last row. Warp gives the bottom margin as a
            // one-based row number, which is also the next row's zero-based index.
            let row = bottom.filter(|&row| top == 1 && row == self.rows - 1);
            if self.status.row != row {
                self.status.text.clear();
            }
            self.status.row = row;
            self.status.writing = false;
        }
    }
    fn set_keypad_application_mode(&mut self) {}
    fn unset_keypad_application_mode(&mut self) {}
    fn set_active_charset(&mut self, _: CharsetIndex) {}
    fn configure_charset(&mut self, _: CharsetIndex, _: StandardCharset) {}
    fn set_color(&mut self, _: usize, _: ColorU) {}
    fn dynamic_color_sequence<W: io::Write>(&mut self, _: &mut W, _: u8, _: usize, _: &str) {}
    fn reset_color(&mut self, _: usize) {}
    fn clipboard_store(&mut self, _: u8, _: &[u8]) {}
    fn clipboard_load(&mut self, _: u8, _: &str) {}
    fn decaln(&mut self) {}
    fn push_title(&mut self) {}
    fn pop_title(&mut self) {}
    fn text_area_size_pixels<W: io::Write>(&mut self, _: &mut W) {}
    fn text_area_size_chars<W: io::Write>(&mut self, _: &mut W) {}
}

fn palette(index: usize) -> Option<u32> {
    const BASIC: [u32; 16] = [
        0x737984, 0xf7768e, 0x9ece6a, 0xe0af68, 0x7aa2f7, 0xbb9af7, 0x7dcfff, 0xc0caf5,
        0x9097a6, 0xff9cab, 0xb9f27c, 0xffc777, 0x9ab8ff, 0xd6b6ff, 0xa4e5ff, 0xffffff,
    ];
    match index {
        0..=15 => Some(BASIC[index]),
        16..=231 => {
            let value = index - 16;
            let channel = |component| if component == 0 { 0 } else { 55 + 40 * component };
            Some(((channel(value / 36) << 16) | (channel((value / 6) % 6) << 8) | channel(value % 6)) as u32)
        }
        232..=255 => Some(((index as u32 - 232) * 10 + 8) * 0x010101),
        _ => None,
    }
}
