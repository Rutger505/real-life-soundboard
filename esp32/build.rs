fn main() {
    // Passes the ESP-IDF link args and kconfig cfgs (`esp_idf_*`) to rustc.
    embuild::espidf::sysenv::output();
    // Set by CONFIG_BTDM_CTRL_LPCLK_SEL_EXT_32K_XTAL; declared so rustc does not
    // warn about an unknown cfg in src/power.rs if it is ever turned off.
    println!("cargo::rustc-check-cfg=cfg(esp_idf_btdm_ctrl_lpclk_sel_ext_32k_xtal)");
}
