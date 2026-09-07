// =====================================================================
//  real-live-soundboard.scad
//  Flat 9-button soundboard enclosure (shirt pocket).
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
//  56.0 x 92.6 mm.
//
//  COMPACTING (nothing is duplicated)
//    - the outer side walls ARE the width retainer: the cavity is the
//      ESP32 plus fit, so no separate side ribs and no side margin
//    - the bands are separated by two full-width dividers instead of a
//      gap plus two sets of corner arms; each divider retains the module
//      in front of it and the module behind it
//    - those dividers also carry the button carrier plate, so the four
//      plate pillars (and the gaps they needed) are gone
//    - the battery sits against the back wall, so it needs side ribs only
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
// The TP4056 is a linear charger, so it burns (Vin - Vbat) x Icharge as heat.
// Standing it off the floor keeps that off the plastic: the case as a whole
// barely warms, but the patch of floor under the chip would otherwise conduct.
chg_lift = 1.5;             // air gap under the charger
chg_foot = 3.0;             // one standoff
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
sep_t     = 2.4;    // band divider: retainer both sides + plate shelf
end_marg  = 1.5;    // keeps the battery off the rear screw bosses
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
dish_d    = 9;      // finger dish in the lid
dish_h    = 1.0;
act_d     = 4.0;    // actuator clearance hole

/* [Status LED] ------------------------------------------------------ */
// 3 mm LED, GPIO2.  Front right, over the power switch: the switch is only
// 4 mm tall so the socket clears it easily, and this is as close to the
// corner as the screw boss allows.  Kept relative to the right-hand wall,
// so it follows the cavity instead of sitting at a fixed number.
led_inset = 5.5;        // socket centre, measured in from the right wall
led_d    = 3.0;         // measured body
led_rim  = 3.3;         // measured lip at the base of the lens, widest part
led_rim_h = 1.2;        // relief that lip drops into
led_clr  = 0.2;
led_sock = 4.0;         // how far the socket hangs below the lid
led_lens = 2.2;         // light hole through the outer surface
led_ch   = 0.6;         // chamfer around that hole

/* [Fasteners] ------------------------------------------------------- */
scr_pilot = 1.7;    // M2 self-tapping pilot
scr_clear = 2.3;
scr_head  = 4.4;
boss_dia  = 2*(corner_r - wall + 0.4);
// the rear pair is slimmed down so it clears the battery, which now runs
// almost to the back wall
boss_dia_r = 4.4;
boss_deep = 8;

// ---------------------------------------------------------------- derived
btn_act = btn_h - btn.z;              // 1.5 actuator above the body
sw_out  = sw_lever_h - swb.z;         // 4.5 lever above the body face
chg_p   = [chg.y, chg.x, chg.z];      // placed: USB-C faces -Y
sw_p    = [swb.z, swb.x, swb.y];      // placed: lever points +X

// the grid is centred on the case, so the ESP32 is what it stands on
cav_h    = max(bat.z + 0.6, btn.z + btn_leg + esp.z + clr_mod);

inner_w = max(esp.x, bat.x) + 2*fit;
b1_y = 0;                    b1_d = chg_p.y;
b2_y = b1_y + b1_d + sep_t;  b2_d = esp.y + 2*fit;
b3_y = b2_y + b2_d + sep_t;  b3_d = bat.y + 2*fit;
inner_l = b3_y + b3_d + end_marg;

out_w  = inner_w + 2*wall;
out_l  = inner_l + 2*wall;
tray_h = floor_t + cav_h;
out_h  = tray_h + top_t;

// component positions, in inner-cavity coordinates
chg_pos  = [(inner_w - chg_p.x)/2, 0];            // hard against the front wall
// hard against the left wall and the front divider, so it needs ribs on two
// sides only and stays clear of the charger's left rib
buck_pos = [fit, b1_d - buck.y - fit];
sw_pos   = [inner_w + wall - 0.4 - sw_out - sw_p.x, 9];
esp_pos  = [(inner_w - esp.x)/2, b2_y + fit];
bat_pos  = [(inner_w - bat.x)/2, b3_y + fit];
btn_c    = [inner_w/2, inner_l/2];
led_pos  = [inner_w - led_inset, 10];

// button pocket / carrier plate geometry
pkt_ow  = btn.x + 2*btn_clr + 2*rib;
// the two band dividers are the shelf the plate rests on
sep_y   = [b1_d, b2_y + b2_d];              // front face of each divider
pil_top = cav_h - btn.z - plate_t;          // shelf height = under the plate
peg_xy  = [for (sx = [-1, 1], i = [0, 1])
              [btn_c.x + sx*16, sep_y[i] + sep_t/2]];
plate_x0 = 0.3;                 plate_x1 = inner_w - 0.3;
plate_y0 = sep_y[0];            plate_y1 = sep_y[1] + sep_t;

boss_xy = [[corner_r, corner_r], [out_w-corner_r, corner_r],
           [corner_r, out_l-corner_r], [out_w-corner_r, out_l-corner_r]];
boss_d  = [boss_dia, boss_dia, boss_dia_r, boss_dia_r];

echo(str("body  ", out_w, " x ", out_l, " x ", out_h, " mm"));
echo(str("battery slack ", cav_h - bat.z, " mm   button-leg space ", btn_leg, " mm"));
// the grid has to land on the two dividers, or the plate is a diving board
assert(btn_c.y - btn_pitch - pkt_ow/2 >= sep_y[0] - 1);
assert(btn_c.y + btn_pitch + pkt_ow/2 <= sep_y[1] + sep_t + 1);
// the LED socket has to stay inside the cavity and off the switch cradle
led_or = led_rim + 2*led_clr + 2*rib;
assert(led_pos.x + led_or/2 <= inner_w);
// the buck-boost and its rib have to stay out of the charger's left rib
assert(buck_pos.x + buck.x + fit + rib <= chg_pos.x - fit - rib);
assert(led_pos.y + led_or/2 <= sep_y[0]);
// it hangs over the switch, so it only has to clear the cradle in Z
assert(cav_h - led_sock >= sw_p.z + weld);

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
// Four pads under the charger corners, inset far enough to miss the corner
// retainers and the USB-C shell.
module charger_feet()
    for (dx = [3, chg_p.x - 3], dy = [3, chg_p.y - 3])
        at(chg_pos.x + dx, chg_pos.y + dy, -weld)
            cylinder(d = chg_foot, h = chg_lift + weld);

module usbc_cut() {
    z = floor_t + chg_lift + 0.4;
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

// Two-sided cradle for the buck-boost: the left wall and the front divider
// hold the other two sides.
module buck_cradle() {
    ox = buck_pos.x - fit; oy = buck_pos.y - fit;
    w = buck.x + 2*fit;  d = buck.y + 2*fit;  h = buck.z + weld;
    at(ox + w, oy - rib, -weld) cube([rib, d + rib, h]);
    at(ox,     oy - rib, -weld) cube([w + rib, rib, h]);
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

module boss(p, d)
    intersection() {
        translate([p.x, p.y, floor_t - weld]) cylinder(d = d, h = cav_h + weld);
        cavity(0, weld);
    }

// Full-width divider between two bands.  It retains the module in front of
// it and the one behind it, and its top face is the shelf that carries the
// button plate - that is what the four separate plate pillars used to do.
module divider(y) {
    intersection() {
        at(-weld, y, -weld) cube([inner_w + 2*weld, sep_t, pil_top + weld]);
        cavity(0, weld);            // trimmed back by the rounded cavity
    }
    for (p = peg_xy) if (p.y > y && p.y < y + sep_t)
        at(p.x, p.y, pil_top) cylinder(d = 2.0, h = 1.0);
}

// Side ribs for a module that is already boxed in front and behind (the
// battery by divider + back wall, the charger by front wall + divider).
module side_ribs(pos, sx, sy, h)
    for (s = [-1, 1])
        at(pos.x + (s < 0 ? -fit - rib : sx + fit), pos.y, -weld)
            cube([rib, sy, h + weld]);

module tray() {
    difference() {
        rrect(out_w, out_l, corner_r, tray_h);
        cavity(1);
        usbc_cut();
        switch_cut();
    }

    // interior features, added after the cavity is cut
    difference() {
        union() {
            for (i = [0:3]) boss(boss_xy[i], boss_d[i]);
            for (y = sep_y) divider(y);
            side_ribs(bat_pos, bat.x, bat.y, bat.z);
            side_ribs(chg_pos, chg_p.x, chg_p.y, chg_lift + chg_p.z);
            charger_feet();
            buck_cradle();
            switch_cradle();
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
    ir = led_rim + 2*led_clr;
    difference() {
        translate([0, 0, -led_sock]) cylinder(d = ir + 2*rib, h = led_sock + weld);
        // the bore grips the body; only the top opens up for the lip
        translate([0, 0, -led_sock - 1]) cylinder(d = io, h = led_sock + 1);
        translate([0, 0, -led_rim_h]) cylinder(d = ir, h = led_rim_h + weld + 1);
    }
}

// Carrier plate: sits under the nine buttons, takes the press force and
// passes it into the two band dividers.  Slots let the (trimmed) legs and
// their wires through.
module plate() {
    translate([0, 0, floor_t + pil_top]) difference() {
        translate([wall + plate_x0, wall + plate_y0, 0])
            rrect(plate_x1 - plate_x0, plate_y1 - plate_y0, 2, plate_t);
        for (i = [-1:1], j = [-1:1])
            translate([wall + btn_c.x + i*btn_pitch, wall + btn_c.y + j*btn_pitch, -1])
                for (s = [-1, 1])
                    translate([s*2.6 - 0.8, -(btn.y + 0.6)/2, 0])
                        cube([1.6, btn.y + 0.6, plate_t + 2]);
        for (p = peg_xy)
            translate([wall + p.x, wall + p.y, -1])
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
        for (i = [0:3])
            translate([boss_xy[i].x, boss_xy[i].y, tray_h - lip_h - 1])
                cylinder(d = boss_d[i] + 1.2, h = lip_h + 2);
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
    %at(chg_pos.x,  chg_pos.y, chg_lift) cube(chg_p);
    %at(buck_pos.x, buck_pos.y) cube(buck);
    %at(sw_pos.x,   sw_pos.y)   union() {
        cube(sw_p);
        translate([sw_p.x, sw_p.y/2 - 1, sw_p.z/2 - 1.2]) cube([sw_out, 2, 2.4]);
    }
    %at(led_pos.x, led_pos.y, cav_h - led_sock) union() {
        cylinder(d = led_d, h = led_sock - led_d/2);
        translate([0, 0, led_sock - led_rim_h]) cylinder(d = led_rim, h = led_rim_h);
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
