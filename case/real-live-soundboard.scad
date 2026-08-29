// =====================================================================
//  real-live-soundboard.scad
//  Flat 9-button soundboard enclosure (wrist-wearable / shirt pocket).
//  Units: mm.  Origin: outside bottom-front-left corner of the tray.
//
//  LAYOUT (looking down, +Y away from the wearer)
//    band 1 (front)  charging module (USB-C exits the front wall) +
//                    buck-boost + power switch
//    band 2          ESP32, with the 3x3 button grid stacked directly
//                    ABOVE it - the grid is centred on the case
//    band 3 (back)   LiPo battery   - nothing above it
//
//  The button grid is centred on the case, so it sits over the ESP32 and
//  the ESP32 is what sets the depth: 5.5 (board) + 0.6 clearance + 3.0
//  (leg space) + 3.5 (button body) = 12.6, so the cavity is 12.6 and the
//  battery gets 4.1 mm of slack (pad it).  Body = 15.4 mm thick,
//  61.2 x 97.3 mm (113.3 mm over the strap lugs).
//
//  THREE PRINTED PARTS
//    tray   holds every module in corner retainers; four corner screw
//           bosses (M2 self-tapping); four pillars that carry the plate
//    lid    button pockets hang from its underside; the actuators sit
//           0.1 mm BELOW the outer surface in a finger dish, so nothing
//           gets pressed while the thing is in a pocket.  The status LED
//           hangs from the same face, in its own socket.
//    plate  button carrier: goes under the nine buttons and takes the
//           press force down into the tray pillars.  Its four pillars
//           drop into the band gaps in front of and behind the ESP32,
//           so nothing lands on the board.
//
//  ASSEMBLY
//    1. trim/bend the button legs to <= 3 mm, solder wires on
//    2. push the nine buttons up into the lid pockets, and the LED into
//       its socket
//    3. drop the plate over them (slots take legs + wires, two holes
//       locate on the pillar pegs)
//    4. fit the modules in the tray, close the lid, 4x M2x8
//
//  !! CHECK BEFORE PRINTING !!
//  Stock 6x6 tactile switches have ~3.5 mm legs, so btn_leg (3 mm) takes
//  them with only a whisker off.  1.8 mm of that sits below the carrier
//  plate, which is also where the soldered wires run.
// =====================================================================

$fa = 2; $fs = 0.4;

/* [Output] --------------------------------------------------------- */
part = "assembly";      // "assembly" | "tray" | "lid" | "plate" | "print"

/* [Measured components] -------------------------------------------- */
bat   = [43,  25,   8.5];   // LiPo cell
esp   = [52,  28.5, 5.5];   // ESP32 board
chg   = [28,  18,   4];     // charger, USB-C centred on an 18 mm edge
buck  = [13,  17,   4];     // buck-boost converter
swb   = [8.5, 4,    3.5];   // switch body
sw_lever_h = 8;             // base -> tip of the slider
btn   = [6,   6,    3.5];   // button body
btn_h = 5;                  // base -> top of the actuator

/* [Shell] ----------------------------------------------------------- */
wall      = 1.6;
floor_t   = 1.2;
top_t     = 1.6;
corner_r  = 4.5;
fit       = 0.4;    // clearance around every component
rib       = 1.2;    // retainer / pocket wall thickness
brk_len   = 5;      // length of a corner retainer arm
side_marg = 3;      // free space each side of the widest board
band_gap  = 4;      // gap between the three bands
end_marg  = 3;
clr_mod   = 0.6;    // vertical clearance over the 4 mm modules
weld      = 0.5;    // overlap used where a feature meets a wall/floor

/* [Lid] ------------------------------------------------------------- */
lip_h   = 2.5;
lip_t   = 1.2;
lip_clr = 0.3;

/* [Buttons] --------------------------------------------------------- */
btn_pitch = 11;     // 3x3 grid pitch -> 5 mm between button bodies
btn_clr   = 0.2;    // per-side clearance in the pocket
plate_t   = 1.2;    // button carrier plate
btn_leg   = 3.0;    // space under a button body: plate + leg/wire room
leg_below = btn_leg - plate_t;     // how far legs may poke below the plate
pil_d     = 3.0;    // pillars that hold the carrier plate up
dish_d    = 9;      // finger dish in the lid
dish_h    = 1.0;
act_d     = 4.0;    // actuator clearance hole

/* [Status LED] ------------------------------------------------------ */
// 3 mm LED, GPIO2.  It lives in the gap between the charger and the power
// switch, the only patch of band 1 that no module and no carrier plate
// reaches, so the socket has the full cavity height to itself.
led_pos  = [44, 14];    // inner-cavity coordinates
led_d    = 3.0;
led_clr  = 0.2;
led_sock = 4.0;         // how far the socket hangs below the lid
led_lens = 2.2;         // light hole through the outer surface
led_ch   = 0.6;         // chamfer around that hole

/* [Fasteners] ------------------------------------------------------- */
scr_pilot = 1.7;    // M2 self-tapping pilot
scr_clear = 2.3;
scr_head  = 4.4;
boss_dia  = 2*(corner_r - wall + 0.4);
boss_deep = 8;

/* [Strap] ----------------------------------------------------------- */
strap_tabs = true;
strap_ext  = 8;     // how far a lug sticks past the body
strap_t    = 4;     // lug thickness
strap_lug  = 18;    // width of one lug
strap_dx   = 16;    // lug centre, offset from the case centreline
strap_slot = [12, 2.5];   // strap slot through a lug

// ---------------------------------------------------------------- derived
btn_act = btn_h - btn.z;              // 1.5 actuator above the body
sw_out  = sw_lever_h - swb.z;         // 4.5 lever above the body face
chg_p   = [chg.y, chg.x, chg.z];      // placed: USB-C faces -Y
sw_p    = [swb.z, swb.x, swb.y];      // placed: lever points +X

// the grid is centred on the case, so the ESP32 is what it stands on
cav_h    = max(bat.z + 0.6, btn.z + btn_leg + esp.z + clr_mod);

inner_w = max(esp.x, bat.x) + 2*side_marg;
b1_y = 0;                     b1_d = chg_p.y;
b2_y = b1_y + b1_d + band_gap; b2_d = esp.y + 2*fit;
b3_y = b2_y + b2_d + band_gap; b3_d = bat.y + 2*fit;
inner_l = b3_y + b3_d + end_marg;

out_w  = inner_w + 2*wall;
out_l  = inner_l + 2*wall;
tray_h = floor_t + cav_h;
out_h  = tray_h + top_t;

// component positions, in inner-cavity coordinates
chg_pos  = [(inner_w - chg_p.x)/2, 0];            // hard against the front wall
buck_pos = [3, 9];
sw_pos   = [inner_w + wall - 0.4 - sw_out - sw_p.x, 9];
esp_pos  = [(inner_w - esp.x)/2, b2_y + fit];
bat_pos  = [(inner_w - bat.x)/2, b3_y + fit];
btn_c    = [inner_w/2, inner_l/2];

// button pocket / carrier plate geometry
pkt_ow  = btn.x + 2*btn_clr + 2*rib;
pil_dx  = btn_pitch + pkt_ow/2 + 0.8;
// the pillars sit in the band gaps either side of the ESP32, not beside
// the buttons: the grid is centred and the board is directly underneath
pil_y   = [b2_y - band_gap/2, b2_y + b2_d + band_gap/2];
pil_xy  = [for (sx = [-1, 1], y = pil_y) [btn_c.x + sx*pil_dx, y]];
pil_top = cav_h - btn.z - plate_t;          // top of a pillar = under the plate
plate_ov = pil_d/2 + 0.5;
plate_x0 = btn_c.x - pil_dx - plate_ov;  plate_x1 = btn_c.x + pil_dx + plate_ov;
plate_y0 = pil_y[0]  - plate_ov;         plate_y1 = pil_y[1]  + plate_ov;

boss_xy = [[corner_r, corner_r], [out_w-corner_r, corner_r],
           [corner_r, out_l-corner_r], [out_w-corner_r, out_l-corner_r]];

echo(str("body  ", out_w, " x ", out_l, " x ", out_h, " mm",
         strap_tabs ? str("  (", out_l + 2*strap_ext, " mm over the strap tabs)") : ""));
echo(str("battery slack ", cav_h - bat.z, " mm   button-leg space ", btn_leg, " mm"));

// ================================================================ helpers
module rrect(sx, sy, r, h)
    linear_extrude(height = h)
        translate([r, r]) offset(r = r) square([sx - 2*r, sy - 2*r]);

// place something in inner-cavity coordinates
module at(x, y, z = 0) translate([wall + x, wall + y, floor_t + z]) children();

module cavity(extra = 0, drop = 0)
    translate([wall, wall, floor_t - drop])
        rrect(inner_w, inner_l, corner_r - wall, cav_h + extra + drop);

// four L-shaped corner retainers around a component footprint
module retainer(x, y, sx, sy, h) {
    ox = x - fit; oy = y - fit; w = sx + 2*fit; d = sy + 2*fit;
    for (mx = [0, 1], my = [0, 1])
        at(ox + mx*w, oy + my*d, -weld)        // weld into the floor
            mirror([mx, 0, 0]) mirror([0, my, 0]) {
                translate([-rib, -rib, 0]) cube([min(brk_len, w/2) + rib, rib, h + weld]);
                translate([-rib, -rib, 0]) cube([rib, min(brk_len, d/2) + rib, h + weld]);
            }
}

// =================================================================== tray
// Two lugs per end.  The gap between them is what lets a USB-C plug reach
// the charger on the front end.
module strap_tab()
    for (sx = [-1, 1]) {
        cx = out_w/2 + sx*strap_dx;
        hull() for (x = [cx - strap_lug/2 + 3, cx + strap_lug/2 - 3],
                    y = [-strap_ext + 3, 4])
            translate([x, y, 0]) cylinder(r = 3, h = strap_t);
    }

module strap_slot_cut()
    for (sx = [-1, 1])
        translate([out_w/2 + sx*strap_dx - strap_slot.x/2, -strap_ext + 2, -1])
            cube([strap_slot.x, strap_slot.y, strap_t + 2]);

module usbc_cut() {
    z = floor_t + 0.4;
    // opening the plug shell passes through
    translate([out_w/2 - 5.25, -1, z]) cube([10.5, wall + 2, 4.2]);
    // shallow relief so a chunky overmould can seat
    translate([out_w/2 - 6.5, -1, z - 1]) cube([13, 1.8, 6.2]);
}

module switch_cut() {
    y = wall + sw_pos.y + sw_p.y/2;
    z = floor_t + sw_p.z/2;
    translate([out_w - wall - 1, y - 2.75, z - 1.5]) cube([wall + 2, 5.5, 3]);
    // finger relief around the slider
    translate([out_w - 0.8, y - 4.5, z - 3]) cube([2, 9, 6]);
}

// The switch gets a three-sided cradle: its +X side stays open so the slider
// can reach the slot in the wall.
module switch_cradle() {
    ox = sw_pos.x - fit; oy = sw_pos.y - fit;
    w = sw_p.x + 2*fit;  d = sw_p.y + 2*fit;  h = sw_p.z + weld;
    at(ox - rib, oy - rib, -weld) cube([rib, d + 2*rib, h]);
    at(ox - rib, oy - rib, -weld) cube([w + rib, rib, h]);
    at(ox - rib, oy + d,   -weld) cube([w + rib, rib, h]);
}

module boss(p)
    intersection() {
        translate([p.x, p.y, floor_t - weld]) cylinder(d = boss_dia, h = cav_h + weld);
        cavity(0, weld);
    }

module tray() {
    difference() {
        union() {
            rrect(out_w, out_l, corner_r, tray_h);
            if (strap_tabs) {
                strap_tab();
                translate([out_w, out_l, 0]) rotate([0, 0, 180]) strap_tab();
            }
        }
        cavity(1);
        usbc_cut();
        switch_cut();
        if (strap_tabs) {
            strap_slot_cut();
            translate([out_w, out_l, 0]) rotate([0, 0, 180]) strap_slot_cut();
        }
    }

    // interior features, added after the cavity is cut
    difference() {
        union() {
            for (p = boss_xy) boss(p);
            retainer(bat_pos.x,  bat_pos.y,  bat.x,    bat.y,    bat.z);
            retainer(esp_pos.x,  esp_pos.y,  esp.x,    esp.y,    esp.z);
            retainer(chg_pos.x,  chg_pos.y,  chg_p.x,  chg_p.y,  chg_p.z);
            retainer(buck_pos.x, buck_pos.y, buck.x,   buck.y,   buck.z);
            switch_cradle();
            for (i = [0:3]) at(pil_xy[i].x, pil_xy[i].y, -weld) {
                cylinder(d = pil_d, h = pil_top + weld);
                if (i == 0 || i == 3)                     // two locating pegs
                    translate([0, 0, pil_top + weld]) cylinder(d = 2.0, h = 1.0);
            }
        }
        for (p = boss_xy)
            translate([p.x, p.y, tray_h - boss_deep]) cylinder(d = scr_pilot, h = boss_deep + 1);
        usbc_cut();
        switch_cut();
    }
}

// ==================================================================== lid
// One button pocket, hanging from the lid underside (local origin = button
// centre at the lid underside).  Open at the bottom: the button is pushed up
// into it and is then held there by the carrier plate.
module btn_pocket() {
    po = btn.x + 2*btn_clr;
    difference() {
        translate([-pkt_ow/2, -pkt_ow/2, -btn.z]) cube([pkt_ow, pkt_ow, btn.z + weld]);
        translate([-po/2, -po/2, -btn.z - 1]) cube([po, po, btn.z + 1]);
    }
}

// Status LED socket, hanging from the lid underside like a button pocket.
// The LED is pushed up until its dome meets the lid and lights through a
// hole too small for it to escape through.
module led_socket() {
    io = led_d + 2*led_clr;
    difference() {
        translate([0, 0, -led_sock]) cylinder(d = io + 2*rib, h = led_sock + weld);
        translate([0, 0, -led_sock - 1]) cylinder(d = io, h = led_sock + 1);
    }
}

// Carrier plate: sits under the nine buttons, takes the press force and
// passes it into the four pillars in the tray floor.  Slots let the
// (trimmed) legs and their wires through.
module plate() {
    translate([0, 0, floor_t + pil_top]) difference() {
        translate([wall + plate_x0, wall + plate_y0, 0])
            rrect(plate_x1 - plate_x0, plate_y1 - plate_y0, 2, plate_t);
        for (i = [-1:1], j = [-1:1])
            translate([wall + btn_c.x + i*btn_pitch, wall + btn_c.y + j*btn_pitch, -1])
                for (s = [-1, 1])
                    translate([s*2.6 - 0.8, -(btn.y + 0.6)/2, 0])
                        cube([1.6, btn.y + 0.6, plate_t + 2]);
        for (k = [0, 3])
            translate([wall + pil_xy[k].x, wall + pil_xy[k].y, -1])
                cylinder(d = 2.4, h = plate_t + 2);
    }
}

module lid_lip() {
    lw = inner_w - 2*lip_clr; ll = inner_l - 2*lip_clr;
    difference() {
        translate([wall + lip_clr, wall + lip_clr, tray_h - lip_h])
            rrect(lw, ll, corner_r - wall - lip_clr, lip_h + weld);
        translate([wall + lip_clr + lip_t, wall + lip_clr + lip_t, tray_h - lip_h - 1])
            rrect(lw - 2*lip_t, ll - 2*lip_t,
                  max(corner_r - wall - lip_clr - lip_t, 0.5), lip_h + 2);
        // no lip across the front: the charger sits against that wall and
        // a USB-C plug has to reach it
        translate([-1, -1, tray_h - lip_h - 1]) cube([out_w + 2, wall + 3, lip_h + 2]);
        for (p = boss_xy)
            translate([p.x, p.y, tray_h - lip_h - 1]) cylinder(d = boss_dia + 1.2, h = lip_h + 2);
    }
}

module lid() {
    difference() {
        union() {
            translate([0, 0, tray_h]) rrect(out_w, out_l, corner_r, top_t);
            lid_lip();
            for (i = [-1:1], j = [-1:1])
                at(btn_c.x + i*btn_pitch, btn_c.y + j*btn_pitch, cav_h) btn_pocket();
            at(led_pos.x, led_pos.y, cav_h) led_socket();
        }
        for (i = [-1:1], j = [-1:1])
            at(btn_c.x + i*btn_pitch, btn_c.y + j*btn_pitch, cav_h) {
                translate([0, 0, -1]) cylinder(d = act_d, h = top_t + 2);   // actuator hole
                translate([0, 0, top_t - dish_h])
                    cylinder(d1 = act_d + 1.5, d2 = dish_d, h = dish_h + 0.01);
            }
        at(led_pos.x, led_pos.y, cav_h) {
            translate([0, 0, -1]) cylinder(d = led_lens, h = top_t + 2);
            translate([0, 0, top_t - led_ch])
                cylinder(d1 = led_lens, d2 = led_lens + 2*led_ch, h = led_ch + 0.01);
        }
        for (p = boss_xy) translate([p.x, p.y, tray_h - 1]) {
            cylinder(d = scr_clear, h = top_t + 2);
            translate([0, 0, top_t + 1 - 1.1]) cylinder(d1 = scr_clear, d2 = scr_head, h = 1.1);
        }
    }
}

// ================================================================ preview
module mock() {
    %at(bat_pos.x,  bat_pos.y)  cube(bat);
    %at(esp_pos.x,  esp_pos.y)  cube(esp);
    %at(chg_pos.x,  chg_pos.y)  cube(chg_p);
    %at(buck_pos.x, buck_pos.y) cube(buck);
    %at(sw_pos.x,   sw_pos.y)   union() {
        cube(sw_p);
        translate([sw_p.x, sw_p.y/2 - 1, sw_p.z/2 - 1.2]) cube([sw_out, 2, 2.4]);
    }
    %at(led_pos.x, led_pos.y, cav_h - led_sock) union() {
        cylinder(d = led_d, h = led_sock - led_d/2);
        translate([0, 0, led_sock - led_d/2]) sphere(d = led_d);
    }
    for (i = [-1:1], j = [-1:1])
        %at(btn_c.x + i*btn_pitch - btn.x/2, btn_c.y + j*btn_pitch - btn.y/2,
            cav_h - btn.z) union() {
            cube(btn);
            translate([btn.x/2, btn.y/2, btn.z]) cylinder(d = 3.5, h = btn_act);
        }
}

// ================================================================= render
if (part == "assembly") { tray(); lid(); plate(); mock(); }
else if (part == "tray")  tray();
else if (part == "plate") translate([-wall - plate_x0, -wall - plate_y0,
                                     -floor_t - pil_top]) plate();
else if (part == "lid")   translate([0, out_l, out_h]) rotate([180, 0, 0]) lid();
else if (part == "print") {
    tray();
    translate([out_w + 10, out_l, out_h]) rotate([180, 0, 0]) lid();
    translate([2*out_w + 20 - wall - plate_x0,
               -wall - plate_y0, -floor_t - pil_top]) plate();
}
