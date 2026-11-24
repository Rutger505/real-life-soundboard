/* Seeed XIAO nRF52840 with its Adafruit UF2 bootloader. The MBR and the
   SoftDevice S140 v7.3.0 slot occupy the flash below 0x27000, and the
   bootloader starts at 0xF4000. The SoftDevice stays disabled: the SoftDevice
   Controller is linked into the firmware. The MBR reserves the first 8 bytes of RAM. */
MEMORY
{
  FLASH : ORIGIN = 0x00027000, LENGTH = 0xF4000 - 0x27000
  RAM   : ORIGIN = 0x20000008, LENGTH = 256K - 8
}
