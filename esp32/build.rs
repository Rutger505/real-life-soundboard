fn main() {
    // Passes the ESP-IDF link args and kconfig cfgs (`esp_idf_*`) to rustc.
    embuild::espidf::sysenv::output();
    // Only set by the sdkconfig.crystal build; declared so the default build
    // does not warn about an unknown cfg in src/power.rs.
    println!("cargo::rustc-check-cfg=cfg(esp_idf_btdm_ctrl_lpclk_sel_ext_32k_xtal)");
}
