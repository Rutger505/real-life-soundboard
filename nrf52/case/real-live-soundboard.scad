// Soundboard enclosure for the XIAO nRF52840 build. Units: mm.
// Origin: outside bottom-front-left corner. +Y points away from the wearer:
// the XIAO band (USB-C out of the left wall, switch in the right wall, status
// LED), then the battery band under the button plate.

$fa = 2; $fs = 0.4;

part = "assembly";      // [assembly, open, tray, lid, plate, print]

/* [Measured components] */
bat   = [43,  25,   8.5];     // the ESP32 build's LiPo
bat_room = [1, 3];            // so the cell and its leads are not squeezed
bat_lead_gap = 10;            // the front rib stops this far short of the side walls, where the leads exit
xiao  = [21,  17.8, 1.0];     // XIAO nRF52840 PCB; USB-C on a 17.8 mm end. Datasheet values, measure the board
xiao_top  = 3.6;              // PCB bottom to the top of the USB-C receptacle
xiao_usb  = [9.0, 3.2];       // receptacle width and height
xiao_usb_over = 0.6;          // receptacle past the PCB edge
xiao_pin_free = 2.5;          // board end to the first castellated pad; the end rails stay inside this
xiao_lift  = 2.0;             // BAT+/BAT- and SWD pads are on the underside: their wires leave under the board
xiao_wire_h = 5;              // wires soldered onto the pins from the top
matrix_wire_h = 3;            // button wires between the cell and the plate
swb   = [8.5, 4,    3.5];
sw_lever_h = 8;
sw_slot_w = 5.5;
sw_stub   = 1.0;              // back rib only at the ends, the pins leave through the middle
btn   = [5.9, 5.9,  3.3];     // base to flat top
btn_nub   = 1.4;
btn_top   = 3.9;              // base to nub tops
btn_act_d = 3.3;
btn_act_h = 4.6;              // base to actuator top
btn_leg   = 3.0;

/* [Shell] */
// Half the ESP32 case's tolerances, like the blinds remote: those printed too loose.
wall      = 1.6;
floor_t   = 1.2;
top_t     = 1.6;
corner_r  = 4.5;
fit       = 0.2;
rib       = 1.2;
brk_len   = 5;
post_t    = 2.4;
clr_mod   = 0.3;
weld      = 0.5;

/* [Lid] */
rim_t   = 0.8;
lid_clr = 0.1;

/* [Buttons] */
btn_pitch = 11;
btn_clr   = 0.1;
btn_hole_clr = 0.15;  // the blinds remote's printed value
plate_t   = 1.2;
plate_lip = 1.2;      // plate reaches past the front posts, so the peg holes keep a wall
pkt_drop  = 0.3;      // pocket walls and ribs stay clear of the lid; only the button tops touch it
dish_d    = 10;
dish_min  = 0.4;      // lid thickness at the cross arm tips
act_d     = 4.4;
nub_clr   = 0.2;      // covers button, plate and lid play together
peg_d     = 2.0;
peg_hole  = 2.2;

/* [Status LED] */
led_from_right = 14;  // LED centre from the right wall, between the XIAO and the switch
led_inset = 5.5;      // from the front wall
led_d    = 3.0;
led_rim  = 3.3;
led_clr  = 0.2;
led_h    = 5.1;
led_slack = 0.3;      // so a taller LED can't hold the lid open
led_bar_t = 0.8;
led_leg_gap = 1.6;
led_lens = 2.2;
led_ch   = 0.4;

/* [Fasteners] */
scr_pilot = 1.7;      // M2 self-tapping
scr_clear = 2.3;
scr_head  = 4.4;
boss_dia  = 2*(corner_r - wall + 0.4);
boss_dia_s = 4.4;     // slimmer next to the XIAO
boss_in   = corner_r - 0.5;     // centre from the outside faces, as close as the countersink allows
boss_deep = 8;

// ---------------------------------------------------------------- derived
sw_out  = sw_lever_h - swb.z;
sw_p    = [swb.z, swb.x, swb.y];      // lever points +X
leg_below = btn_leg - plate_t;

// The XIAO band only needs the board and its wires; the battery band stacks
// the cell, the matrix wires and the buttons.
cav_h = max(xiao_lift + xiao_top + clr_mod + xiao_wire_h,
            bat.z + clr_mod + matrix_wire_h + btn_leg + btn.z);

bat_pkt = [bat.x + bat_room.x + 2*fit, bat.y + bat_room.y + 2*fit];
inner_w = bat_pkt.x;                  // the side walls hold the cell

// boss reach into the cavity, from the inner walls
boss_d     = [boss_dia_s, boss_dia, boss_dia, boss_dia];   // front left, front right, rear left, rear right
boss_reach = [for (d = boss_d) boss_in - wall + d/2];

// XIAO band: the receptacle touches the left wall, clear of the front-left boss.
xiao_pos = [xiao_usb_over, boss_reach[0] + fit + rib + fit];
xiao_x1  = xiao_pos.x + xiao.x;
b1_d     = xiao_pos.y + xiao.y + fit + rib;     // its back brackets end at the post line

// Battery band: behind the middle posts, up to the rear bosses.
b2_y    = b1_d + post_t;
bat_end = b2_y + bat_pkt.y;                     // back of the pocket = front of the rear beam
beam_y  = bat_end;
inner_l = beam_y + max(boss_reach[2], boss_reach[3]) + fit;
bat_pos = [(inner_w - bat.x)/2, b2_y + (bat_pkt.y - bat.y)/2];

out_w  = inner_w + 2*wall;
out_l  = inner_l + 2*wall;
tray_h = floor_t + cav_h;
out_h  = tray_h + top_t;
lid_o  = rim_t + lid_clr;

sw_pos  = [inner_w + wall - 0.4 - sw_out - sw_p.x, 9];
led_pos = [inner_w - led_from_right, led_inset];

pil_top = cav_h - btn.z - plate_t;
beam_x  = [boss_reach[2] + fit, inner_w - boss_reach[3] - fit];
peg_xy  = [[brk_len/2, b1_d + post_t/2], [inner_w - brk_len/2, b1_d + post_t/2],
           [beam_x[0] + brk_len/2, beam_y + post_t/2], [beam_x[1] - brk_len/2, beam_y + post_t/2]];
plate_x0 = 0.3;          plate_x1 = inner_w - 0.3;
plate_y0 = b1_d - plate_lip;   plate_y1 = beam_y + post_t;
plate_rib_x0 = brk_len + fit;

btn_c   = [inner_w/2, (plate_y0 + plate_y1)/2];
btn_po  = btn.x + 2*btn_hole_clr;
pkt_ow  = btn.x + 2*btn_clr + 2*rib;
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
led_seat = cav_h - led_h - led_slack;
led_bot  = led_seat - led_bar_t;
led_gus  = led_bot - (led_pos.y + led_or/2);

usb_y = xiao_pos.y + xiao.y/2;
usb_z = xiao_lift + xiao_top - xiao_usb.y/2;     // receptacle centre, cavity coordinates

boss_xy = [[boss_in, boss_in], [out_w-boss_in, boss_in],
           [boss_in, out_l-boss_in], [out_w-boss_in, out_l-boss_in]];

echo(str("body ", out_w, " x ", out_l, " x ", out_h));

assert(xiao_x1 + fit + rib <= led_pos.x - led_or/2 - 1);           // end stop clear of the LED webs
assert(led_pos.x + led_or/2 + 1 <= sw_pos.x - fit - rib);          // LED clear of the switch cradle
assert(led_pos.y + led_or/2 <= sw_pos.y + sw_p.y + fit + rib + 5);
assert(led_gus >= 1);                                               // the webs stay off the floor
assert(led_leg_gap <= led_d - 1);
assert(sw_pos.y - fit - rib >= boss_reach[1] + fit);               // switch cradle clear of the front-right boss
assert(sw_pos.y + sw_p.y + fit + rib <= b1_d);
assert(usb_y - 6.5 >= boss_reach[0] + 1);                          // USB-C relief clear of the boss
assert(xiao_pin_free >= 2);
assert(pil_top >= bat.z + clr_mod + leg_below);
assert(plate_rib_w >= rib);
assert(btn_c.x - btn_pitch - pkt_ow/2 >= max(boss_reach[2], boss_reach[3]) + fit);  // grid clear of the plate's corner notches
assert(btn_c.y - btn_pitch - pkt_ow/2 - rib >= plate_y0);
assert(peg_xy[0].x + peg_hole/2 + 0.4 <= plate_rib_x0);
assert(bat_pkt.x - 2*bat_lead_gap >= 10);
assert(act_rise < top_t);
assert(dish_min >= 0.4);
assert(cross_w >= 2);
assert(dish_d < btn_pitch);
assert(sqrt(2)*(corner_r - boss_in) + scr_head/2 + 0.5 <= corner_r - lid_o);   // countersink keeps 0.5 of lid corner

// ================================================================ helpers
module rrect(sx, sy, r, h)
    linear_extrude(height = h)
        translate([r, r]) offset(r = r) square([sx - 2*r, sy - 2*r]);

// inner-cavity coordinates
module at(x, y, z = 0) translate([wall + x, wall + y, floor_t + z]) children();

module cavity(extra = 0, drop = 0, grow = 0)
    translate([wall - grow, wall - grow, floor_t - drop])
        rrect(inner_w + 2*grow, inner_l + 2*grow, corner_r - wall + grow, cav_h + extra + drop);

module each_key()
    for (i = [-1:1], j = [-1:1])
        translate([btn_c.x + i*btn_pitch, btn_c.y + j*btn_pitch]) children();

// =================================================================== tray
module usbc_cut() {
    y = wall + usb_y;
    z = floor_t + usb_z;
    translate([-1, y - (xiao_usb.x + 1)/2, z - (xiao_usb.y + 1)/2])
        cube([wall + 2, xiao_usb.x + 1, xiao_usb.y + 1]);
    translate([-1, y - 6.5, z - 3.1]) cube([1.8, 13, 6.2]);      // overmould relief
}

module switch_cut() {
    y = wall + sw_pos.y + sw_p.y/2;
    z = floor_t + sw_p.z/2;
    translate([out_w - wall - 1, y - sw_slot_w/2, z - 1.5]) cube([wall + 2, sw_slot_w, 3]);
    translate([out_w - 0.8, y - 4.5, -1]) cube([2, 9, z + 4]);       // finger relief, open at the bed so no one-layer lip
}

// The board rests on two rails under its short ends, inside the pin-free strips,
// so the wires from the BAT and SWD pads leave underneath. Short corner ribs hold
// it sideways, the far end stops it when a cable is plugged in. A dab of hot glue
// on the rails keeps it down.
module xiao_cradle() {
    x0 = xiao_pos.x; y0 = xiao_pos.y;
    rail_w = xiao_pin_free - 0.5;
    h = xiao_lift + xiao.z;
    for (x = [x0, xiao_x1 - rail_w])
        at(x, y0, -weld) cube([rail_w, xiao.y, xiao_lift + weld]);
    for (x = [x0, xiao_x1 - rail_w], y = [y0 - fit - rib, y0 + xiao.y + fit])
        at(x, y, -weld) cube([rail_w, rib, h + weld]);
    at(xiao_x1 + fit, y0 - fit - rib, -weld) cube([rib, xiao.y + 2*fit + 2*rib, h + weld]);
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

// The hull fills the corner behind the boss, so a slim boss is still tied to the walls.
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

// Plate supports: a post on each side wall between the bands, and a beam across
// the back of the battery pocket, between the rear bosses.
module plate_supports() {
    for (x = [-weld, inner_w - brk_len])
        at(x, b1_d, -weld) cube([brk_len + weld, post_t, pil_top + weld]);
    at(beam_x[0] - weld, beam_y, -weld)
        cube([beam_x[1] - beam_x[0] + 2*weld, post_t, pil_top + weld]);
    for (p = peg_xy) at(p.x, p.y, pil_top) cylinder(d = peg_d, h = 1.0);
}

// The side walls and the beam hold the cell; the front rib leaves the corners
// open for the leads.
module battery_rib()
    at(bat_lead_gap, b2_y - rib, -weld) cube([inner_w - 2*bat_lead_gap, rib, bat.z + weld]);

// Hangs off the front wall. Nothing faces straight down: the bars continue as
// 45 degree webs to the wall, and the leg gap between them is open below with a
// pointed roof.
module led_bracket() {
    x0 = led_pos.x - led_or/2;
    web_w = (led_or - led_leg_gap)/2;
    len = led_pos.y + led_or/2 + weld;
    difference() {
        union() {
            at(x0, -weld, led_bot) cube([led_or, len, cav_h - led_bot]);
            for (x = [x0, x0 + led_or - web_w])
                hull() {
                    at(x, -weld, led_bot) cube([web_w, len, 0.01]);
                    at(x, -weld, led_gus) cube([web_w, weld + 0.01, 0.01]);
                }
        }
        at(led_pos.x, led_pos.y, led_seat) cylinder(d = led_ir, h = cav_h - led_seat + 1);
        at(led_pos.x, len + 1, 0)
            rotate([90, 0, 0]) linear_extrude(height = len + 3)
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
            xiao_cradle();
            switch_cradle();
            plate_supports();
            battery_rib();
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

// The edge ribs keep the plate from sagging across the full width. The rear
// corners are notched around the bosses.
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
            translate([p.x, p.y, -1]) cylinder(d = peg_hole, h = plate_t + pkt_h + 2);
        for (x = [-1, beam_x[1]])
            translate([x, beam_y, -1]) cube([beam_x[0] + 1, inner_l, plate_t + pkt_h + 2]);
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

module xiao_mock() {
    cube(xiao);
    translate([-xiao_usb_over, (xiao.y - xiao_usb.x)/2, xiao_top - xiao_usb.y])
        cube([7.3, xiao_usb.x, xiao_usb.y]);
    translate([8, 3, xiao.z]) cube([10, 10, 1.5]);          // module can
}

module mock() {
    %at(bat_pos.x, bat_pos.y) cube(bat);
    %at(xiao_pos.x, xiao_pos.y, xiao_lift) xiao_mock();
    %at(sw_pos.x, sw_pos.y) union() {
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
