// =====================================================================
//  real-live-soundboard.scad
//  Flat 9-button soundboard enclosure (shirt pocket).
//  Units: mm.  Origin: outside bottom-front-left corner of the tray.
//
//  LAYOUT (looking down, +Y away from the wearer)
//    band 1 (front)  charging module (USB-C exits the front wall) +
//                    buck-boost + power switch
//    band 2          ESP32, with the 3x3 button grid stacked ABOVE it on
//                    a carrier plate
//    band 3 (back)   LiPo battery, nothing above it
//
//  The ESP32 sets the depth: 5.5 (board) + 0.6 clearance + 8.0 (room for
//  the wires soldered to its pins) + 3.0 (button legs, plate included) +
//  5.1 (button pocket: 3.9 button, 0.4 actuator over the nubs, 0.8 cap
//  flange) = 22.2.
//
//  OPEN SIDES
//    - the ESP32 is held by four corner posts against the side walls, so
//      both pin rows are open along their length for wires to leave
//      sideways
//    - those same four posts carry the button plate.  Its front and back
//      edges have ribs underneath, because it spans the full width between
//      the walls
//    - the battery pocket is 1 mm wider and 3 mm longer than the cell.  At
//      both front corners the front rib and the side rib stop 10 mm short,
//      so the leads can bend without being pinched
//    - band 1 has short retaining arms behind its modules, not a wall
//
//  KEY CAPS
//  A bare button actuator is 3.3 mm across, too small to find by feel.
//  Each button gets a printed cap with a 7 mm top that slides in a bore in
//  the lid.  A square flange under the lid keeps the cap from falling out,
//  and its flat underside rests on the actuator.  The actuator stands 0.4 mm
//  over the corner nubs, so if a cap is pushed past the switch travel the
//  flange lands on the nubs instead of crushing the switch.
//
//  FOUR PRINTED PARTS
//    tray   modules in ribs and corner arms; four corner screw bosses
//           (M2 self-tapping); four corner posts that carry the plate
//    lid    a block of nine button pockets hangs from its underside.  The
//           status LED hangs from the same face, in its own socket.
//    plate  button carrier: goes under the nine buttons and takes the
//           press force out to the four corner posts
//    caps   nine of them, printed flange down
//
//  ASSEMBLY
//    1. cut one leg from each side of every button, leave 3 mm
//    2. lid face down: drop the nine caps into the bores, flange last
//    3. push the nine buttons in on top of them, and the LED into its socket
//    4. drop the plate over them (slots take the legs, two holes each
//       side locate on the post pegs), wire the matrix on its underside
//    5. fit the modules in the tray, close the lid, 4x M2x8
// =====================================================================

$fa = 2; $fs = 0.4;

/* [Output] --------------------------------------------------------- */
// "open" is the assembly with the lid left off, to look into the tray
part = "assembly";      // "assembly" | "open" | "tray" | "lid" | "plate" | "caps" | "print"

/* [Measured components] -------------------------------------------- */
bat   = [43,  25,   8.5];   // LiPo cell
bat_room = [1, 3];          // extra pocket over the cell, so it and its leads are not squeezed
bat_lead_gap = 10;          // front and side ribs stop this far short of each front corner
esp   = [52,  28.5, 5.5];   // ESP32 board
esp_wire_h = 8;             // room above the board for wires soldered to its pins
chg   = [28,  18,   4];     // charger, USB-C centred on an 18 mm edge
// The TP4056 is a linear charger, so it burns (Vin - Vbat) x Icharge as heat.
// Standing it off the floor keeps that off the plastic: the case as a whole
// barely warms, but the patch of floor under the chip would otherwise conduct.
chg_lift = 1.5;             // air gap under the charger
chg_foot = 3.0;             // one standoff
buck  = [13,  17,   4];     // buck-boost converter
swb   = [8.5, 4,    3.5];   // switch body
sw_lever_h = 8;             // base -> tip of the slider
btn   = [5.9, 5.9,  3.3];   // button body, base to the flat top
btn_nub   = 1.5;            // square corner nubs on top of the body
btn_top   = 3.9;            // base -> top of the nubs
btn_act_d = 3.3;            // round actuator
btn_act_h = 4.3;            // base -> top of the actuator (1 mm over the body)
btn_leg   = 3.0;            // legs below the base

/* [Shell] ----------------------------------------------------------- */
wall      = 1.6;
floor_t   = 1.2;
top_t     = 1.6;
corner_r  = 4.5;
fit       = 0.4;    // clearance around every component
rib       = 1.2;    // retainer / pocket wall thickness
brk_len   = 5;      // length of a corner post or retaining arm, along the wall
post_t    = 2.4;    // corner post depth, between bands
clr_mod   = 0.6;    // vertical clearance over the boards
weld      = 0.5;    // overlap used where a feature meets a wall/floor

/* [Lid] ------------------------------------------------------------- */
lip_h   = 2.5;
lip_t   = 1.2;
lip_clr = 0.3;

/* [Buttons] --------------------------------------------------------- */
btn_pitch = 11;     // 3x3 grid pitch -> 5.1 mm between button bodies
btn_clr   = 0.2;    // per-side clearance in the pocket
plate_t   = 1.2;    // button carrier plate
plate_rib = 4;      // depth of the stiffening ribs under its front and back edges
leg_below = btn_leg - plate_t;     // how far legs poke below the plate

/* [Key caps] -------------------------------------------------------- */
cap_d     = 7.0;    // round top the finger presses
cap_clr   = 0.2;    // per side, in the lid bore and in the flange recess
cap_fl    = 8.1;    // square flange under the lid that keeps the cap in
cap_fl_t  = 0.8;
cap_sink  = 0.3;    // top sits this far below the lid surface, so a pocket can't press it
cap_dish  = 0.4;    // fingertip dish in the top
cap_edge  = 0.4;    // chamfer on the top edge
cap_lead  = 0.8;    // chamfer around the bore, leads the finger in

/* [Status LED] ------------------------------------------------------ */
// 3 mm LED, GPIO18.  Front right, over the power switch: the switch is only
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
// the rear pair is slimmed down so it gives the battery pocket more room
boss_dia_r = 4.4;
boss_deep = 8;

// ---------------------------------------------------------------- derived
sw_out  = sw_lever_h - swb.z;         // 4.5 lever above the body face
chg_p   = [chg.y, chg.x, chg.z];      // placed: USB-C faces -Y
sw_p    = [swb.z, swb.x, swb.y];      // placed: lever points +X

// Lid underside down to the nub tops: the flange, then the actuator standing
// over the nubs.  The cap rests on the actuator with its flange against the
// lid, and the plate holds the button up against it, so nothing rattles.
nub_z    = cap_fl_t + (btn_act_h - btn_top);
pocket_h = nub_z + btn_top;                     // lid underside -> plate top
cap_h    = cap_fl_t + top_t - cap_sink;
cap_bore = cap_d + 2*cap_clr;
cap_rec  = cap_fl + 2*cap_clr;
btn_po   = btn.x + 2*btn_clr;
// 45 degree step from the flange recess down to the button pocket, so the
// lid prints upside down without an overhang
cap_step = (cap_rec - btn_po)/2;

cav_h = max(bat.z + 0.6, esp.z + clr_mod + esp_wire_h + btn_leg + pocket_h);

bat_pkt = [bat.x + bat_room.x + 2*fit, bat.y + bat_room.y + 2*fit];
inner_w = max(esp.x + 2*fit, bat_pkt.x);

b1_y = 0;                          b1_d = chg_p.y;
b2_y = b1_d + post_t;              b2_d = esp.y + 2*fit;
b3_y = b2_y + b2_d + post_t + rib; b3_d = bat_pkt.y;

// The rear bosses sit in the back corners, so the pocket stops where its
// corner would run into one.
boss_ctr = corner_r - wall;
bat_pkt_x = (inner_w - bat_pkt.x)/2;
boss_dx   = bat_pkt_x - boss_ctr;
end_marg  = boss_ctr
          + (boss_dx < boss_dia_r/2 ? sqrt(pow(boss_dia_r/2, 2) - pow(boss_dx, 2)) : 0);
inner_l = b3_y + b3_d + end_marg;

out_w  = inner_w + 2*wall;
out_l  = inner_l + 2*wall;
tray_h = floor_t + cav_h;
out_h  = tray_h + top_t;

// component positions, in inner-cavity coordinates
chg_pos  = [(inner_w - chg_p.x)/2, 0];            // hard against the front wall
// hard against the left wall, with a rib on its right and front
buck_pos = [fit, b1_d - buck.y - fit];
sw_pos   = [inner_w + wall - 0.4 - sw_out - sw_p.x, 9];
esp_pos  = [(inner_w - esp.x)/2, b2_y + fit];
bat_pos  = [(inner_w - bat.x)/2, b3_y + (b3_d - bat.y)/2];
led_pos  = [inner_w - led_inset, 10];

// corner posts and the carrier plate they hold up
post_y   = [b1_d, b2_y + b2_d];                 // front face of each pair
pil_top  = cav_h - pocket_h - plate_t;          // post top = under the plate
peg_xy   = [for (x = [brk_len/2, inner_w - brk_len/2], y = post_y) [x, y + post_t/2]];
plate_x0 = 0.3;          plate_x1 = inner_w - 0.3;
plate_y0 = post_y[0];    plate_y1 = post_y[1] + post_t;
plate_rib_x0 = brk_len + fit;                   // ribs stop short of the posts

btn_c   = [inner_w/2, (plate_y0 + plate_y1)/2];
pkt_ow  = cap_rec + 2*rib;
key_blk = 2*btn_pitch + pkt_ow;                 // the nine pockets merge into one block
slot_l  = btn.y + 0.6;

boss_xy = [[corner_r, corner_r], [out_w-corner_r, corner_r],
           [corner_r, out_l-corner_r], [out_w-corner_r, out_l-corner_r]];
boss_d  = [boss_dia, boss_dia, boss_dia_r, boss_dia_r];

echo(str("body  ", out_w, " x ", out_l, " x ", out_h, " mm"));
echo(str("battery pocket ", bat_pkt.x, " x ", bat_pkt.y, "   height slack ", cav_h - bat.z, " mm"));
echo(str("free height between ESP32 and plate ribs ", pil_top - plate_rib - esp.z, " mm"));
// the grid, its leg slots and the plate ribs have to share the plate
assert(btn_c.y + btn_pitch + pkt_ow/2 <= plate_y1);
assert(btn_c.y + btn_pitch + slot_l/2 <= plate_y1 - rib);
assert(btn_c.x + btn_pitch + pkt_ow/2 <= plate_x1);
// the plate ribs must stay above the board and its wires
assert(pil_top - plate_rib >= esp.z + clr_mod + leg_below);
// the battery ribs need something left beside the lead gaps
assert(bat_pkt.x - 2*bat_lead_gap >= 10);
assert(bat_pkt.y - bat_lead_gap >= 10);
// the pocket still has to hold the button body straight below the step
assert(btn_top - cap_step >= 2);
// the flange has to hold the cap in the bore
assert(cap_fl - cap_bore >= 0.6);
// the LED socket has to stay inside the cavity and inside band 1
led_or = led_rim + 2*led_clr + 2*rib;
assert(led_pos.x + led_or/2 <= inner_w);
assert(led_pos.y + led_or/2 <= b1_d);
// the buck-boost and its rib have to stay out of the charger's left rib
assert(buck_pos.x + buck.x + fit + rib <= chg_pos.x - fit - rib);
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

// =================================================================== tray
// Four pads under the charger corners, inset far enough to miss the ribs
// and the USB-C shell.
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

// Charger: side ribs, the front wall, and a short arm at each back corner.
module charger_ribs() {
    h = chg_lift + chg_p.z;
    for (s = [-1, 1])
        at(chg_pos.x + (s < 0 ? -fit - rib : chg_p.x + fit), chg_pos.y, -weld)
            cube([rib, chg_p.y, h + weld]);
    at(chg_pos.x - fit - rib, b1_d, -weld) cube([brk_len + rib, rib, h + weld]);
    at(chg_pos.x + chg_p.x + fit - brk_len, b1_d, -weld) cube([brk_len + rib, rib, h + weld]);
}

// Buck-boost: the left wall, a rib on its right and front, an arm at its
// back right corner.  The front left corner post covers its back left.
module buck_cradle() {
    ox = buck_pos.x - fit; oy = buck_pos.y - fit;
    w = buck.x + 2*fit;  d = buck.y + 2*fit;  h = buck.z + weld;
    at(ox + w, oy - rib, -weld) cube([rib, d + 2*rib, h]);
    at(ox,     oy - rib, -weld) cube([w + rib, rib, h]);
    at(ox + w - brk_len, oy + d, -weld) cube([brk_len + rib, rib, h]);
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

// Four posts against the side walls, one at each corner of the ESP32.  They
// hold the board front to back (the walls hold it side to side) and carry
// the button plate.  Everything between them is open for wires.
module esp_posts()
    for (x = [-weld, inner_w - brk_len], y = post_y) {
        at(x, y, -weld) cube([brk_len + weld, post_t, pil_top + weld]);
        at(x < 0 ? brk_len/2 : inner_w - brk_len/2, y + post_t/2, pil_top)
            cylinder(d = 2.0, h = 1.0);
    }

// Side, front and back ribs.  The leads come out at the front corners, so
// both the front rib and the side ribs stop short of them.
module battery_ribs() {
    x0 = bat_pkt_x; x1 = x0 + bat_pkt.x;
    y0 = b3_y;      y1 = y0 + bat_pkt.y;
    h = bat.z + weld;
    for (x = [x0 - rib, x1])
        at(x, y0 + bat_lead_gap, -weld) cube([rib, bat_pkt.y - bat_lead_gap + rib, h]);
    at(x0 - rib, y1, -weld) cube([bat_pkt.x + 2*rib, rib, h]);
    at(x0 + bat_lead_gap, y0 - rib, -weld) cube([bat_pkt.x - 2*bat_lead_gap, rib, h]);
}

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
            esp_posts();
            battery_ribs();
            charger_ribs();
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
module each_key() {
    for (i = [-1:1], j = [-1:1])
        at(btn_c.x + i*btn_pitch, btn_c.y + j*btn_pitch, cav_h) children();
}

// The nine pockets, merged into one block under the lid.  Its bottom face
// rests on the carrier plate.
module key_block()
    at(btn_c.x - key_blk/2, btn_c.y - key_blk/2, cav_h - pocket_h)
        cube([key_blk, key_blk, pocket_h + weld]);

// What one key takes out of the lid and the block (local origin = key centre
// at the lid underside), from the top down: the lead-in chamfer, the bore the
// cap slides in, the recess its flange moves in, the step, the button pocket.
module key_cut() {
    translate([0, 0, top_t - cap_lead])
        cylinder(d1 = cap_bore, d2 = cap_bore + 2*cap_lead, h = cap_lead + 0.01);
    translate([0, 0, -1]) cylinder(d = cap_bore, h = top_t + 2);
    translate([-cap_rec/2, -cap_rec/2, -nub_z]) cube([cap_rec, cap_rec, nub_z + 0.01]);
    hull() {
        translate([-cap_rec/2, -cap_rec/2, -nub_z]) cube([cap_rec, cap_rec, 0.01]);
        translate([-btn_po/2, -btn_po/2, -nub_z - cap_step]) cube([btn_po, btn_po, 0.01]);
    }
    translate([-btn_po/2, -btn_po/2, -pocket_h - 1]) cube([btn_po, btn_po, pocket_h + 1]);
}

// Key cap, flange face down at the origin, which is also how it prints.
module cap() {
    fl_r = 0.6;
    dish_a = cap_d/2 - cap_edge - 0.4;
    dish_r = (pow(dish_a, 2) + pow(cap_dish, 2)) / (2*cap_dish);
    difference() {
        union() {
            linear_extrude(height = cap_fl_t)
                offset(r = fl_r) square(cap_fl - 2*fl_r, center = true);
            rotate_extrude()
                polygon([[0, 0], [cap_d/2, 0], [cap_d/2, cap_h - cap_edge],
                         [cap_d/2 - cap_edge, cap_h], [0, cap_h]]);
        }
        translate([0, 0, cap_h - cap_dish + dish_r]) sphere(r = dish_r, $fa = 1);
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

// Carrier plate: sits under the nine buttons and rests on the four corner
// posts.  The ribs under its front and back edges keep it from sagging
// across the full width.  Slots let the legs through.
module plate() {
    translate([0, 0, floor_t + pil_top]) difference() {
        union() {
            translate([wall + plate_x0, wall + plate_y0, 0])
                rrect(plate_x1 - plate_x0, plate_y1 - plate_y0, 2, plate_t);
            for (y = [plate_y0, plate_y1 - rib])
                translate([wall + plate_rib_x0, wall + y, -plate_rib])
                    cube([inner_w - 2*plate_rib_x0, rib, plate_rib + weld]);
        }
        for (i = [-1:1], j = [-1:1])
            translate([wall + btn_c.x + i*btn_pitch, wall + btn_c.y + j*btn_pitch, -1])
                for (s = [-1, 1])
                    translate([s*2.6 - 0.8, -slot_l/2, 0])
                        cube([1.6, slot_l, plate_t + 2]);
        for (p = peg_xy)
            translate([wall + p.x, wall + p.y, -1])
                cylinder(d = 2.4, h = plate_t + 2);
    }
}

// flat face down, ribs up
module plate_print()
    translate([-wall - plate_x0, wall + plate_y1, floor_t + pil_top + plate_t])
        rotate([180, 0, 0]) plate();

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
            key_block();
            at(led_pos.x, led_pos.y, cav_h) led_socket();
        }
        each_key() key_cut();
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
    %at(led_pos.x, led_pos.y, cav_h - led_sock) union() {
        cylinder(d = led_d, h = led_sock - led_d/2);
        translate([0, 0, led_sock - led_rim_h]) cylinder(d = led_rim, h = led_rim_h);
        translate([0, 0, led_sock - led_d/2]) sphere(d = led_d);
    }
    each_key() %translate([-btn.x/2, -btn.y/2, -pocket_h]) button_mock();
}

module caps_in_place() each_key() translate([0, 0, -cap_fl_t]) cap();

module caps_print()
    for (i = [0:2], j = [0:2])
        translate([cap_fl/2 + i*(cap_fl + 3), cap_fl/2 + j*(cap_fl + 3), 0]) cap();

// ================================================================= render
if (part == "assembly") { tray(); lid(); plate(); caps_in_place(); mock(); }
else if (part == "open") { tray(); plate(); mock(); }
else if (part == "tray")  tray();
else if (part == "plate") plate_print();
else if (part == "caps")  caps_print();
else if (part == "lid")   translate([0, out_l, out_h]) rotate([180, 0, 0]) lid();
else if (part == "print") {
    tray();
    translate([out_w + 10, out_l, out_h]) rotate([180, 0, 0]) lid();
    translate([2*out_w + 20, 0, 0]) plate_print();
    translate([2*out_w + 20, plate_y1 - plate_y0 + 10, 0]) caps_print();
}
