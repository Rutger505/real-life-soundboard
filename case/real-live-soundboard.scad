// Soundboard enclosure. Units: mm. Origin: outside bottom-front-left corner.
// +Y points away from the wearer: charger band, ESP32 band, battery band.

$fa = 2; $fs = 0.4;

part = "assembly";      // "assembly" | "open" | "tray" | "lid" | "plate" | "print"

/* [Measured components] */
bat   = [43,  25,   8.5];
bat_room = [1, 3];          // so the cell and its leads are not squeezed
bat_lead_gap = 10;          // ribs stop this far short of the front corners, where the leads exit
esp   = [52,  28.5, 5.5];
esp_wire_h = 8;             // wires soldered onto the pins
chg   = [28,  18,   4];     // USB-C centred on an 18 mm edge
chg_lift = 1.5;             // keeps the TP4056's heat off the floor
chg_foot = 3.0;
chg_end_fit = 0.2;          // small, so the USB-C receptacle stays near the wall
buck  = [13,  17,   4];
swb   = [8.5, 4,    3.5];
sw_lever_h = 8;
sw_slot_w = 5.5;
sw_stub   = 1.0;            // back rib only at the ends, the pins leave through the middle
sw_pin_l  = 2.5;            // pins trimmed short, plus the solder joint, before the buck-boost
btn   = [5.9, 5.9,  3.3];   // base to flat top
btn_nub   = 1.4;
btn_top   = 3.9;            // base to nub tops
btn_act_d = 3.3;
btn_act_h = 4.6;            // base to actuator top
btn_leg   = 3.0;

/* [Shell] */
wall      = 1.6;
floor_t   = 1.2;
top_t     = 1.6;
corner_r  = 4.5;
fit       = 0.4;
rib       = 1.2;
brk_len   = 5;
post_t    = 2.4;
clr_mod   = 0.6;
weld      = 0.5;

/* [Lid] */
rim_t   = 0.8;
lid_clr = 0.2;

/* [Buttons] */
btn_pitch = 11;
btn_clr   = 0.2;
plate_t   = 1.2;
plate_lip = 1.2;    // plate reaches past the posts, so the peg holes keep a wall
pkt_drop  = 0.3;    // pocket walls and ribs stay clear of the lid; only the button tops touch it
leg_below = btn_leg - plate_t;
dish_d    = 10;
dish_min  = 0.4;    // lid thickness at the cross arm tips
act_d     = 4.4;
nub_clr   = 0.4;    // covers button, plate and lid play together

/* [Status LED] */
led_inset = 5.5;        // from the right wall
led_d    = 3.0;
led_rim  = 3.3;
led_clr  = 0.2;
led_h    = 5.1;
led_slack = 0.3;        // so a taller LED can't hold the lid open
led_bar_t = 0.8;
led_leg_gap = 1.6;
led_lens = 2.2;
led_ch   = 0.4;

/* [Fasteners] */
scr_pilot = 1.7;    // M2 self-tapping
scr_clear = 2.3;
scr_head  = 4.4;
boss_dia  = 2*(corner_r - wall + 0.4);
boss_dia_s = 4.4;   // slimmer, where the charger or the battery sits in the corner
boss_in   = corner_r - 0.5;     // centre from the outside faces, as close as the countersink allows
boss_deep = 8;

// ---------------------------------------------------------------- derived
sw_out  = sw_lever_h - swb.z;
chg_p   = [chg.x, chg.y, chg.z];      // USB-C faces -X
sw_p    = [swb.z, swb.x, swb.y];      // lever points +X

cav_h = max(bat.z + 0.6, esp.z + clr_mod + esp_wire_h + btn_leg + btn.z);

bat_pkt = [bat.x + bat_room.x + 2*fit, bat.y + bat_room.y + 2*fit];
inner_w = max(esp.x + 2*fit, bat_pkt.x);

boss_reach = boss_in - wall + boss_dia_s/2;
chg_pos  = [0, boss_reach + fit];

b1_y = 0;                          b1_d = chg_pos.y + chg_p.y + fit;
b2_y = b1_d + post_t;              b2_d = esp.y + 2*fit;
b3_y = b2_y + b2_d + post_t + rib; b3_d = bat_pkt.y;

bat_pkt_x = (inner_w - bat_pkt.x)/2;
inner_l = b3_y + b3_d;

out_w  = inner_w + 2*wall;
out_l  = inner_l + 2*wall;
tray_h = floor_t + cav_h;
out_h  = tray_h + top_t;
lid_o  = rim_t + lid_clr;

buck_pos = [chg_p.x + chg_end_fit + rib + fit, b1_d - buck.y - fit];
sw_pos   = [inner_w + wall - 0.4 - sw_out - sw_p.x, 9];
esp_pos  = [(inner_w - esp.x)/2, b2_y + fit];
bat_pos  = [(inner_w - bat.x)/2, b3_y + (b3_d - bat.y)/2];
led_pos  = [inner_w - led_inset, 10];

post_y   = [b1_d, b2_y + b2_d];
pil_top  = cav_h - btn.z - plate_t;
peg_xy   = [for (x = [brk_len/2, inner_w - brk_len/2], y = post_y) [x, y + post_t/2]];
plate_x0 = 0.3;          plate_x1 = inner_w - 0.3;
plate_y0 = post_y[0] - plate_lip;    plate_y1 = post_y[1] + post_t + plate_lip;
plate_rib_x0 = brk_len + fit;

btn_c   = [inner_w/2, (plate_y0 + plate_y1)/2];
btn_po  = btn.x + 2*btn_clr;
pkt_ow  = btn_po + 2*rib;
pkt_h   = btn.z - pkt_drop;
slot_l  = btn.y + 0.6;
plate_rib_w = plate_y1 - (btn_c.y + btn_pitch + pkt_ow/2) + 0.01;

nub_w    = btn_nub + 2*nub_clr;
nub_c    = btn.x/2 - btn_nub/2;
act_rise = btn_act_h - btn.z;
dish_h   = top_t - dish_min;
cross_w  = 2*(nub_c - nub_w/2);

led_ir   = led_rim + 2*led_clr;
led_or   = led_ir + 2*rib;
led_x0   = led_pos.x - led_or/2;
led_seat = cav_h - led_h - led_slack;
led_bot  = led_seat - led_bar_t;
led_gus  = led_bot - (inner_w - led_x0);

boss_xy = [[boss_in, boss_in], [out_w-boss_in, boss_in],
           [boss_in, out_l-boss_in], [out_w-boss_in, out_l-boss_in]];
boss_d  = [boss_dia_s, boss_dia, boss_dia_s, boss_dia_s];

echo(str("body ", out_w, " x ", out_l, " x ", out_h));

assert(plate_rib_w >= rib);
assert(btn_c.x + btn_pitch + pkt_ow/2 <= plate_x1);
assert(pil_top >= esp.z + clr_mod + leg_below);
assert(bat_pkt.x - 2*bat_lead_gap >= 10);
assert(bat_pkt.y - bat_lead_gap >= 10);
assert(act_rise < top_t);
assert(dish_min >= 0.4);
assert(cross_w >= 2);
assert(dish_d < btn_pitch);
assert(led_pos.y + led_or/2 <= b1_d);
assert(led_gus >= sw_p.z + weld);
assert(led_leg_gap <= led_d - 1);
assert(buck_pos.x + buck.x + fit <= sw_pos.x - sw_pin_l);
assert(boss_reach < bat_pos.x);
assert(sqrt(2)*(corner_r - boss_in) + scr_head/2 + 0.5 <= corner_r - lid_o);   // countersink keeps 0.5 of lid corner

// ================================================================ helpers
module rrect(sx, sy, r, h)
    linear_extrude(height = h)
        translate([r, r]) offset(r = r) square([sx - 2*r, sy - 2*r]);

// inner-cavity coordinates
module at(x, y, z = 0) translate([wall + x, wall + y, floor_t + z]) children();

module cavity(extra = 0, drop = 0)
    translate([wall, wall, floor_t - drop])
        rrect(inner_w, inner_l, corner_r - wall, cav_h + extra + drop);

module each_key()
    for (i = [-1:1], j = [-1:1])
        translate([btn_c.x + i*btn_pitch, btn_c.y + j*btn_pitch]) children();

// =================================================================== tray
module charger_feet()
    for (dx = [3, chg_p.x - 3], dy = [3, chg_p.y - 3])
        at(chg_pos.x + dx, chg_pos.y + dy, -weld)
            cylinder(d = chg_foot, h = chg_lift + weld);

module usbc_cut() {
    y = wall + chg_pos.y + chg_p.y/2;
    z = floor_t + chg_lift + 0.4;
    translate([-1, y - 5.25, z]) cube([wall + 2, 10.5, 4.2]);
    translate([-1, y - 6.5, z - 1]) cube([1.8, 13, 6.2]);     // overmould relief
}

module switch_cut() {
    y = wall + sw_pos.y + sw_p.y/2;
    z = floor_t + sw_p.z/2;
    translate([out_w - wall - 1, y - sw_slot_w/2, z - 1.5]) cube([wall + 2, sw_slot_w, 3]);
    translate([out_w - 0.8, y - 4.5, -1]) cube([2, 9, z + 4]);       // finger relief, open at the bed so no one-layer lip
}

module charger_ribs() {
    h = chg_lift + chg_p.z;
    x1 = chg_pos.x + chg_p.x + chg_end_fit;
    y0 = chg_pos.y - fit - rib;
    y1 = chg_pos.y + chg_p.y + fit;
    for (y = [y0, y1])
        at(-weld, y, -weld) cube([x1 + rib + weld, rib, h + weld]);
    for (y = [y0, y1 - brk_len])
        at(x1, y, -weld) cube([rib, brk_len + rib, h + weld]);
}

// The charger's end arms hold the left side. The right side only gets corner
// arms, so the switch pins have room between them.
module buck_cradle() {
    ox = buck_pos.x - fit; oy = buck_pos.y - fit;
    w = buck.x + 2*fit;  d = buck.y + 2*fit;  h = buck.z + weld;
    for (y = [oy - rib, oy + d])
        at(ox, y, -weld) cube([w + rib, rib, h]);
    for (y = [oy - rib, oy + d + rib - brk_len])
        at(ox + w, y, -weld) cube([rib, brk_len, h]);
}

// The stops beside the wall slot keep the body from sliding out towards the wall.
module switch_cradle() {
    ox = sw_pos.x - fit; oy = sw_pos.y - fit;
    w = sw_p.x + 2*fit;  d = sw_p.y + 2*fit;  h = sw_p.z + weld;
    stop_d = rib + (d - sw_slot_w)/2;
    stop_w = inner_w + weld - (ox + w);
    for (y = [oy - rib, oy + d - sw_stub])
        at(ox - rib, y, -weld) cube([rib, rib + sw_stub, h]);
    at(ox - rib, oy - rib, -weld) cube([w + rib, rib, h]);
    at(ox - rib, oy + d,   -weld) cube([w + rib, rib, h]);
    for (y = [oy - rib, oy + d + rib - stop_d])
        at(ox + w, y, -weld) cube([stop_w, stop_d, h]);
}

// The hull fills the corner behind the boss, so a slim rear boss is still tied to the walls.
module boss(p, d) {
    c = [p.x < out_w/2 ? 0 : out_w, p.y < out_l/2 ? 0 : out_l];
    intersection() {
        hull() {
            translate([p.x, p.y, floor_t - weld]) cylinder(d = d, h = cav_h + weld);
            translate([min(p.x, c.x), min(p.y, c.y), floor_t - weld])
                cube([abs(p.x - c.x), abs(p.y - c.y), cav_h + weld]);
        }
        cavity(0, weld);
    }
}

// only at the corners, so wires can leave the pin rows sideways
module esp_posts()
    for (x = [-weld, inner_w - brk_len], y = post_y) {
        at(x, y, -weld) cube([brk_len + weld, post_t, pil_top + weld]);
        at(x < 0 ? brk_len/2 : inner_w - brk_len/2, y + post_t/2, pil_top)
            cylinder(d = 2.0, h = 1.0);
    }

// The back wall of the case is the back of the pocket.
module battery_ribs() {
    x0 = bat_pkt_x; x1 = x0 + bat_pkt.x;
    y0 = b3_y;
    h = bat.z + weld;
    for (x = [x0 - rib, x1])
        at(x, y0 + bat_lead_gap, -weld) cube([rib, bat_pkt.y - bat_lead_gap + weld, h]);
    at(x0 + bat_lead_gap, y0 - rib, -weld) cube([bat_pkt.x - 2*bat_lead_gap, rib, h]);
}

// Nothing faces straight down: the bars continue as 45 degree webs to the
// wall, and the leg gap between them is open below with a pointed roof.
module led_bracket() {
    y0 = led_pos.y - led_or/2;
    web_w = (led_or - led_leg_gap)/2;
    len = inner_w - led_x0 + weld;
    difference() {
        union() {
            at(led_x0, y0, led_bot) cube([len, led_or, cav_h - led_bot]);
            for (y = [y0, y0 + led_or - web_w])
                hull() {
                    at(led_x0, y, led_bot) cube([len, web_w, 0.01]);
                    at(inner_w - 0.01, y, led_gus) cube([weld + 0.01, web_w, 0.01]);
                }
        }
        at(led_pos.x, led_pos.y, led_seat) cylinder(d = led_ir, h = cav_h - led_seat + 1);
        at(led_x0 - 1, led_pos.y, 0)
            rotate([90, 0, 90]) linear_extrude(height = len + 1)
                polygon([[-led_leg_gap/2, led_gus - 1], [led_leg_gap/2, led_gus - 1],
                         [led_leg_gap/2, led_seat], [0, led_seat + led_leg_gap/2],
                         [-led_leg_gap/2, led_seat]]);
    }
}

module tray() {
    difference() {
        rrect(out_w, out_l, corner_r, out_h);
        cavity(top_t + 1);
        translate([rim_t, rim_t, tray_h])
            rrect(out_w - 2*rim_t, out_l - 2*rim_t, corner_r - rim_t, top_t + 1);
        usbc_cut();
        switch_cut();
    }

    difference() {
        union() {
            for (i = [0:3]) boss(boss_xy[i], boss_d[i]);
            esp_posts();
            battery_ribs();
            charger_ribs();
            charger_feet();
            buck_cradle();
            switch_cradle();
            led_bracket();
        }
        for (p = boss_xy)
            translate([p.x, p.y, tray_h - boss_deep]) cylinder(d = scr_pilot, h = boss_deep + 1);
        usbc_cut();
        switch_cut();
    }
}

// ================================================================== plate
module btn_pocket()
    difference() {
        translate([-pkt_ow/2, -pkt_ow/2, -weld]) cube([pkt_ow, pkt_ow, pkt_h + weld]);
        translate([-btn_po/2, -btn_po/2, -1]) cube([btn_po, btn_po, btn.z + 2]);
    }

// The edge ribs keep the plate from sagging across the full width.
module plate() {
    translate([wall, wall, floor_t + pil_top]) difference() {
        union() {
            translate([plate_x0, plate_y0, 0])
                rrect(plate_x1 - plate_x0, plate_y1 - plate_y0, 2, plate_t);
            each_key() translate([0, 0, plate_t]) btn_pocket();
            for (y = [plate_y0, plate_y1 - plate_rib_w])
                translate([plate_rib_x0, y, plate_t - weld])
                    cube([inner_w - 2*plate_rib_x0, plate_rib_w, pkt_h + weld]);
        }
        each_key()
            for (s = [-1, 1])
                translate([s*2.6 - 0.8, -slot_l/2, -1])
                    cube([1.6, slot_l, plate_t + 1.01]);
        for (p = peg_xy)
            translate([p.x, p.y, -1]) cylinder(d = 2.4, h = plate_t + 2);
    }
}

module plate_print()
    translate([-wall - plate_x0, -wall - plate_y0, -floor_t - pil_top]) plate();

// ==================================================================== lid
// The nub holes leave a cross that the button's flat top bears on.
module key_cut() {
    translate([0, 0, -1]) cylinder(d = act_d, h = top_t + 2);
    translate([0, 0, dish_min]) cylinder(d1 = act_d, d2 = dish_d, h = dish_h + 0.01);
    for (sx = [-1, 1], sy = [-1, 1])
        translate([sx*nub_c - nub_w/2, sy*nub_c - nub_w/2, -1])
            cube([nub_w, nub_w, top_t + 2]);
}

module lid() {
    difference() {
        translate([lid_o, lid_o, tray_h])
            rrect(out_w - 2*lid_o, out_l - 2*lid_o, corner_r - lid_o, top_t);
        each_key() translate([wall, wall, tray_h]) key_cut();
        at(led_pos.x, led_pos.y, cav_h) {
            translate([0, 0, -1]) cylinder(d = led_lens, h = top_t + 2);
            translate([0, 0, top_t - led_ch])
                cylinder(d1 = led_lens, d2 = led_lens + 2*led_ch, h = led_ch + 0.01);
        }
        for (p = boss_xy) translate([p.x, p.y, tray_h - 1]) {
            cylinder(d = scr_clear, h = top_t + 2);
            translate([0, 0, top_t + 1 - 1.1]) cylinder(d1 = scr_clear, d2 = scr_head, h = 1.1 + 0.01);
        }
    }
}

module lid_print() translate([-lid_o, -lid_o, -tray_h]) lid();

// ================================================================ preview
module button_mock() {
    cube(btn);
    for (x = [0, btn.x - btn_nub], y = [0, btn.y - btn_nub])
        translate([x, y, btn.z]) cube([btn_nub, btn_nub, btn_top - btn.z]);
    translate([btn.x/2, btn.y/2, btn.z]) cylinder(d = btn_act_d, h = btn_act_h - btn.z);
    for (s = [-1, 1])
        translate([btn.x/2 + s*2.6 - 0.3, btn.y/2 + s*2.25 - 0.4, -btn_leg])
            cube([0.6, 0.8, btn_leg]);
}

module mock() {
    %at(bat_pos.x,  bat_pos.y)  cube(bat);
    %at(esp_pos.x,  esp_pos.y)  cube(esp);
    %at(chg_pos.x,  chg_pos.y, chg_lift) cube(chg_p);
    %at(buck_pos.x, buck_pos.y) cube(buck);
    %at(sw_pos.x,   sw_pos.y)   union() {
        cube(sw_p);
        translate([sw_p.x, sw_p.y/2 - 1, sw_p.z/2 - 1.2]) cube([sw_out, 2, 2.4]);
    }
    %at(led_pos.x, led_pos.y, led_seat) union() {
        cylinder(d = led_rim, h = 1);
        cylinder(d = led_d, h = led_h - led_d/2);
        translate([0, 0, led_h - led_d/2]) sphere(d = led_d);
        for (s = [-1, 1]) translate([s*1.27 - 0.25, -0.25, -10]) cube([0.5, 0.5, 10]);
    }
    each_key() %at(-btn.x/2, -btn.y/2, cav_h - btn.z) button_mock();
}

// ================================================================= render
if (part == "assembly") { tray(); lid(); plate(); mock(); }
else if (part == "open") { tray(); plate(); mock(); }
else if (part == "tray")  tray();
else if (part == "plate") plate_print();
else if (part == "lid")   lid_print();
else if (part == "print") {
    tray();
    translate([out_w + 10, 0, 0]) lid_print();
    translate([2*out_w + 20, 0, 0]) plate_print();
}
