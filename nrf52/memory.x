/* nRF52840, flashed over SWD from address 0: no UF2 bootloader, no SoftDevice.
 * The SoftDevice Controller is linked into the firmware instead. */
MEMORY
{
  FLASH : ORIGIN = 0x00000000, LENGTH = 1024K
  RAM   : ORIGIN = 0x20000000, LENGTH = 256K
}
