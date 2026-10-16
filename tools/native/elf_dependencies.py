"""Read direct ELF64 DT_NEEDED names without relying on section headers."""

from dataclasses import dataclass
from pathlib import Path
import struct


PT_LOAD = 1
PT_DYNAMIC = 2
DT_NULL = 0
DT_NEEDED = 1
DT_STRTAB = 5
DT_STRSZ = 10
ELF_HEADER = struct.Struct("<16sHHIQQQIHHHHHH")
PHDR = struct.Struct("<IIQQQQQQ")
DYNAMIC = struct.Struct("<qQ")


@dataclass(frozen=True)
class ELFDependencies:
    machine: int
    elf_type: int
    needed: tuple[str, ...]


def _span_end(start: int, size: int, limit: int, label: str) -> int:
    if start < 0 or size < 0 or start > limit or size > limit - start:
        raise ValueError(f"{label} range is outside the file")
    return start + size


def _map_vaddr(vaddr: int, loads: list[tuple[int, int, int, int]], file_size: int) -> int:
    matches = []
    for offset, seg_vaddr, filesz, _memsz in loads:
        if seg_vaddr <= vaddr < seg_vaddr + filesz:
            mapped = offset + (vaddr - seg_vaddr)
            _span_end(mapped, 1, file_size, "mapped address")
            matches.append(mapped)
    if len(matches) != 1:
        raise ValueError("virtual address has no unique file-backed PT_LOAD mapping")
    return matches[0]


def read_elf64_dependencies(path: str | Path, *, supported_machines=(62, 183),
                             supported_types=(2, 3)) -> ELFDependencies:
    data = Path(path).read_bytes()
    size = len(data)
    if size < ELF_HEADER.size:
        raise ValueError("truncated ELF header")
    ident, elf_type, machine, version, _entry, phoff, _shoff, _flags, ehsize, phentsize, phnum, _shentsize, _shnum, _shstrndx = ELF_HEADER.unpack_from(data)
    if ident[:4] != b"\x7fELF" or ident[4] != 2 or ident[5] != 1 or ident[6] != 1:
        raise ValueError("unsupported ELF class, byte order, or version")
    if elf_type not in supported_types or machine not in supported_machines:
        raise ValueError("unsupported ELF type or machine")
    if ehsize != ELF_HEADER.size or phentsize != PHDR.size:
        raise ValueError("invalid ELF or program-header entry size")
    if phnum == 0:
        raise ValueError("ELF has no program headers")
    ph_end = _span_end(phoff, phentsize * phnum, size, "program-header table")
    if ph_end <= phoff:
        raise ValueError("invalid program-header table")

    loads = []
    dynamic = None
    for index in range(phnum):
        offset = phoff + index * phentsize
        p_type, _flags, p_offset, p_vaddr, _paddr, p_filesz, p_memsz, align = PHDR.unpack_from(data, offset)
        _span_end(p_offset, p_filesz, size, "program segment")
        if p_memsz < p_filesz:
            raise ValueError("PT_LOAD memory size is smaller than file size")
        if p_type == PT_LOAD:
            loads.append((p_offset, p_vaddr, p_filesz, p_memsz))
        elif p_type == PT_DYNAMIC:
            if dynamic is not None:
                raise ValueError("multiple PT_DYNAMIC segments")
            dynamic = (p_offset, p_filesz)
    if not loads:
        raise ValueError("ELF has no PT_LOAD segment")
    if dynamic is None:
        return ELFDependencies(machine, elf_type, ())
    dyn_offset, dyn_size = dynamic
    if dyn_size == 0 or dyn_size % DYNAMIC.size:
        raise ValueError("invalid PT_DYNAMIC size")
    tags = {}
    needed_offsets = []
    found_null = False
    for index in range(dyn_size // DYNAMIC.size):
        tag, value = DYNAMIC.unpack_from(data, dyn_offset + index * DYNAMIC.size)
        if tag == DT_NULL:
            found_null = True
            break
        if tag in (DT_STRTAB, DT_STRSZ):
            if tag in tags and tags[tag] != value:
                raise ValueError("conflicting dynamic string-table metadata")
            tags[tag] = value
        elif tag == DT_NEEDED:
            needed_offsets.append(value)
    if not found_null:
        raise ValueError("PT_DYNAMIC has no DT_NULL")
    if not needed_offsets:
        return ELFDependencies(machine, elf_type, ())
    if DT_STRTAB not in tags or DT_STRSZ not in tags or tags[DT_STRSZ] == 0:
        raise ValueError("DT_NEEDED requires DT_STRTAB and DT_STRSZ")
    strtab = _map_vaddr(tags[DT_STRTAB], loads, size)
    strsz = tags[DT_STRSZ]
    _span_end(strtab, strsz, size, "dynamic string table")
    needed = []
    for string_offset in needed_offsets:
        if string_offset >= strsz:
            raise ValueError("DT_NEEDED string offset is outside DT_STRSZ")
        end = data.find(b"\0", strtab + string_offset, strtab + strsz)
        if end < 0 or end == strtab + string_offset:
            raise ValueError("unterminated or empty DT_NEEDED string")
        try:
            name = data[strtab + string_offset:end].decode("ascii")
        except UnicodeDecodeError as exc:
            raise ValueError("DT_NEEDED name is not ASCII") from exc
        needed.append(name)
    return ELFDependencies(machine, elf_type, tuple(needed))
