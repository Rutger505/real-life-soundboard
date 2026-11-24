use std::path::PathBuf;
use std::process::ExitCode;
use std::{env, fs};

use elf2uf2::{NRF52840_FAMILY, find_bootloader_drive, load_segments, to_uf2};

/// Usage: `elf2uf2 <firmware.elf>`
///
/// Writes the UF2 next to the ELF. When a UF2 bootloader drive is mounted,
/// also copies it there, which flashes the board.
fn main() -> ExitCode {
    let Some(elf_path) = env::args_os().nth(1).map(PathBuf::from) else {
        eprintln!("Usage: elf2uf2 <firmware.elf>");
        eprintln!("To build and flash the firmware, run `cargo run --release` inside nrf52/.");
        return ExitCode::FAILURE;
    };

    match convert(elf_path) {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("error: {error}");
            ExitCode::FAILURE
        }
    }
}

fn convert(elf_path: PathBuf) -> Result<(), Box<dyn std::error::Error>> {
    let elf = fs::read(&elf_path).map_err(|e| format!("{}: {e}", elf_path.display()))?;
    let uf2 = to_uf2(&load_segments(&elf)?, NRF52840_FAMILY);

    let uf2_path = elf_path.with_extension("uf2");
    fs::write(&uf2_path, &uf2)?;
    println!("Wrote {}", uf2_path.display());

    match find_bootloader_drive() {
        Some(drive) => {
            let target = drive.join(uf2_path.file_name().unwrap());
            fs::write(&target, &uf2)?;
            println!("Flashed through {}", drive.display());
        }
        None => println!(
            "No UF2 drive mounted. Double tap reset on the board, then drag the file onto it."
        ),
    }
    Ok(())
}
