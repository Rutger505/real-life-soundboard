//! Converts firmware ELF files to UF2, the format of drag and drop
//! bootloaders: <https://github.com/microsoft/uf2>.

use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};

use object::elf::PT_LOAD;
use object::read::elf::{ElfFile32, ProgramHeader};

/// The family ID that the Adafruit nRF52 bootloader accepts.
pub const NRF52840_FAMILY: u32 = 0xADA5_2840;

const MAGIC_START_0: u32 = 0x0A32_4655;
const MAGIC_START_1: u32 = 0x9E5D_5157;
const MAGIC_END: u32 = 0x0AB1_6F30;
const FLAG_FAMILY_ID_PRESENT: u32 = 0x0000_2000;
pub const BLOCK_LEN: usize = 512;
pub const PAYLOAD_LEN: usize = 256;
const DATA_FIELD_LEN: usize = 476;

#[derive(Debug)]
pub enum Error {
    Elf(object::Error),
    SegmentOutsideFile,
}

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Error::Elf(error) => write!(f, "not a valid 32 bit ELF file: {error}"),
            Error::SegmentOutsideFile => write!(f, "a segment points outside the ELF file"),
        }
    }
}

impl std::error::Error for Error {}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Segment {
    pub address: u32,
    pub data: Vec<u8>,
}

/// The bytes that must end up in flash: every loadable segment with file
/// contents, at its load (physical) address. Initial values of `.data` are
/// stored in flash, so the virtual address would point into RAM.
pub fn load_segments(elf: &[u8]) -> Result<Vec<Segment>, Error> {
    let file = ElfFile32::<object::Endianness>::parse(elf).map_err(Error::Elf)?;
    let endian = file.endian();
    let mut segments = Vec::new();
    for header in file.elf_program_headers() {
        if header.p_type(endian) != PT_LOAD || header.p_filesz(endian) == 0 {
            continue;
        }
        let data = header
            .data(endian, elf)
            .map_err(|()| Error::SegmentOutsideFile)?;
        segments.push(Segment {
            address: header.p_paddr(endian),
            data: data.to_vec(),
        });
    }
    Ok(segments)
}

/// Splits the segments over 256 byte flash pages, one UF2 block each.
/// Unused bytes of a page are zero.
pub fn to_uf2(segments: &[Segment], family: u32) -> Vec<u8> {
    let mut pages: BTreeMap<u32, [u8; PAYLOAD_LEN]> = BTreeMap::new();
    for segment in segments {
        for (offset, &byte) in segment.data.iter().enumerate() {
            let address = segment.address + offset as u32;
            let page = address - address % PAYLOAD_LEN as u32;
            pages.entry(page).or_insert([0; PAYLOAD_LEN])[(address - page) as usize] = byte;
        }
    }

    let block_count = pages.len() as u32;
    let mut uf2 = Vec::with_capacity(pages.len() * BLOCK_LEN);
    for (block_number, (address, payload)) in pages.iter().enumerate() {
        for word in [
            MAGIC_START_0,
            MAGIC_START_1,
            FLAG_FAMILY_ID_PRESENT,
            *address,
            PAYLOAD_LEN as u32,
            block_number as u32,
            block_count,
            family,
        ] {
            uf2.extend_from_slice(&word.to_le_bytes());
        }
        uf2.extend_from_slice(payload);
        uf2.extend_from_slice(&[0; DATA_FIELD_LEN - PAYLOAD_LEN]);
        uf2.extend_from_slice(&MAGIC_END.to_le_bytes());
    }
    uf2
}

/// A mounted UF2 bootloader drive, recognised by its `INFO_UF2.TXT`.
pub fn find_bootloader_drive() -> Option<PathBuf> {
    let user = std::env::var("USER").unwrap_or_default();
    let mount_roots = [
        PathBuf::from("/run/media").join(&user),
        PathBuf::from("/media").join(&user),
        PathBuf::from("/media"),
        PathBuf::from("/Volumes"),
    ];
    mount_roots
        .iter()
        .filter_map(|root| fs::read_dir(root).ok())
        .flatten()
        .flatten()
        .map(|entry| entry.path())
        .find(|drive| is_bootloader_drive(drive))
}

fn is_bootloader_drive(path: &Path) -> bool {
    path.join("INFO_UF2.TXT").is_file()
}

#[cfg(test)]
mod tests {
    use super::*;

    const LOAD: u32 = 1;
    const NOTE: u32 = 4;

    fn word(block: &[u8], index: usize) -> u32 {
        u32::from_le_bytes(block[index * 4..index * 4 + 4].try_into().unwrap())
    }

    /// A minimal 32 bit little endian ARM ELF with the given program headers,
    /// as (type, physical address, data).
    fn elf(segments: &[(u32, u32, &[u8])]) -> Vec<u8> {
        const HEADER_LEN: u32 = 52;
        const PROGRAM_HEADER_LEN: u32 = 32;

        let mut elf = vec![0x7F, b'E', b'L', b'F', 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0];
        let u16s = |elf: &mut Vec<u8>, values: &[u16]| {
            values
                .iter()
                .for_each(|v| elf.extend_from_slice(&v.to_le_bytes()))
        };
        let u32s = |elf: &mut Vec<u8>, values: &[u32]| {
            values
                .iter()
                .for_each(|v| elf.extend_from_slice(&v.to_le_bytes()))
        };
        u16s(&mut elf, &[2, 40]);
        u32s(&mut elf, &[1, 0, HEADER_LEN, 0, 0]);
        u16s(
            &mut elf,
            &[
                HEADER_LEN as u16,
                PROGRAM_HEADER_LEN as u16,
                segments.len() as u16,
                40,
                0,
                0,
            ],
        );

        let mut offset = HEADER_LEN + PROGRAM_HEADER_LEN * segments.len() as u32;
        for &(kind, address, data) in segments {
            let len = data.len() as u32;
            u32s(
                &mut elf,
                &[kind, offset, address + 0x1000, address, len, len, 5, 4],
            );
            offset += len;
        }
        for &(_, _, data) in segments {
            elf.extend_from_slice(data);
        }
        elf
    }

    #[test]
    fn loads_segments_at_their_physical_address() {
        let elf = elf(&[(LOAD, 0x26000, &[1, 2, 3]), (LOAD, 0x26100, &[4])]);
        assert_eq!(
            load_segments(&elf).unwrap(),
            [
                Segment {
                    address: 0x26000,
                    data: vec![1, 2, 3]
                },
                Segment {
                    address: 0x26100,
                    data: vec![4]
                },
            ]
        );
    }

    #[test]
    fn skips_segments_that_are_not_loaded_or_empty() {
        let elf = elf(&[(NOTE, 0x0, &[9]), (LOAD, 0x2000_0000, &[])]);
        assert_eq!(load_segments(&elf).unwrap(), []);
    }

    #[test]
    fn rejects_something_that_is_not_an_elf() {
        assert!(load_segments(b"not an elf").is_err());
    }

    #[test]
    fn writes_one_block_per_page() {
        let segments = [Segment {
            address: 0x26000,
            data: (0..=255).chain(0..10).collect(),
        }];
        let uf2 = to_uf2(&segments, NRF52840_FAMILY);
        assert_eq!(uf2.len(), 2 * BLOCK_LEN);

        let blocks: Vec<_> = uf2.chunks(BLOCK_LEN).collect();
        for (number, block) in blocks.iter().enumerate() {
            assert_eq!(word(block, 0), MAGIC_START_0);
            assert_eq!(word(block, 1), MAGIC_START_1);
            assert_eq!(word(block, 2), FLAG_FAMILY_ID_PRESENT);
            assert_eq!(word(block, 4), 256);
            assert_eq!(word(block, 5), number as u32);
            assert_eq!(word(block, 6), 2);
            assert_eq!(word(block, 7), NRF52840_FAMILY);
            assert_eq!(word(block, 127), MAGIC_END);
        }
        assert_eq!(word(blocks[0], 3), 0x26000);
        assert_eq!(word(blocks[1], 3), 0x26100);
        assert_eq!(&blocks[0][32..32 + 256], (0..=255).collect::<Vec<u8>>());
        assert_eq!(&blocks[1][32..42], (0..10).collect::<Vec<u8>>());
        assert!(blocks[1][42..32 + 476].iter().all(|&b| b == 0));
    }

    #[test]
    fn unaligned_segments_share_a_page() {
        let segments = [
            Segment {
                address: 0x26010,
                data: vec![0xAA; 4],
            },
            Segment {
                address: 0x260FE,
                data: vec![0xBB; 4],
            },
        ];
        let uf2 = to_uf2(&segments, NRF52840_FAMILY);
        let blocks: Vec<_> = uf2.chunks(BLOCK_LEN).collect();
        assert_eq!(blocks.len(), 2);
        assert_eq!(&blocks[0][32 + 0x10..32 + 0x14], [0xAA; 4]);
        assert_eq!(&blocks[0][32 + 0xFE..32 + 0x100], [0xBB; 2]);
        assert_eq!(word(blocks[1], 3), 0x26100);
        assert_eq!(&blocks[1][32..34], [0xBB; 2]);
    }

    #[test]
    fn recognises_a_bootloader_drive() {
        let drive = std::env::temp_dir().join(format!("elf2uf2-test-{}", std::process::id()));
        fs::create_dir_all(&drive).unwrap();
        assert!(!is_bootloader_drive(&drive));
        fs::write(drive.join("INFO_UF2.TXT"), "UF2 Bootloader").unwrap();
        assert!(is_bootloader_drive(&drive));
        fs::remove_dir_all(&drive).unwrap();
    }
}
