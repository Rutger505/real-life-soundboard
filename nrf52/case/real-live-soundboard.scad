// Soundboard enclosure for the XIAO nRF52840 build. Units: mm.
// Origin: outside bottom-front-left corner. +Y points away from the wearer:
// the front band (switch in the left wall, status LED, XIAO with USB-C out of
// the right wall), then the battery band under the button plate.
// The rear bosses and the front-right one hang from the lid line, so the cell
// and the XIAO reach into the corners below them.

$fa = 2; $fs = 0.4;

part = "assembly";      // [assembly, open, tray, lid, plate, print]

/* [Measured components] */
bat   = [43,  25,   8.5];     // the ESP32 build's LiPo
bat_room = [1, 3];            // so the cell and its leads are not squeezed
bat_lead_gap = 10;            // the front rib starts this far from the left wall: leads exit at the switch end
xiao  = [21,  17.8, 1.0];     // XIAO nRF52840 PCB; USB-C on a 17.8 mm end. Datasheet values, measure the board
xiao_top  = 3.6;              // PCB bottom to the top of the USB-C receptacle
xiao_usb  = [9.0, 3.2];       // receptacle width and height
xiao_usb_over = 0.6;          // receptacle past the PCB edge
xiao_pin_free = 2.5;          // board end to the first castellated pad; the supports stay inside this
xiao_pin1 = 2.88;             // board end to the centre of D0 (7 pads at 2.54, centred)
xiao_lift  = 2.0;             // BAT+/BAT- and SWD pads are on the underside: their wires leave under the board
xiao_wire_h = 5;              // wires soldered onto the pins from the top
xiao_back_clr = 0.4;          // D0-D6 face the cell: room for the solder on the castellations
matrix_wire_h = 3;            // button wires between the cell and the plate
swb   = [8.5, 4,    3.5];
sw_lever_h = 8;
sw_slot_w = 5.5;
sw_stub   = 1.0;              // back rib only at the ends, the pins leave through the middle
sw_fit    = 0.1;              // fit printed 0.2 mm too loose for the switch
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
cav_r     = 1.2;              // inside corners, tight so the cell and the XIAO fit into them
fit       = 0.2;
rib       = 1.2;
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
plate_lip = 1.2;      // plate reaches past the front corbels, so the peg holes keep a wall
pkt_drop  = 0.3;      // pocket walls and ribs stay clear of the lid; only the button tops touch it
dish_d    = 10;
dish_min  = 0.4;      // lid thickness at the cross arm tips
act_d     = 4.4;
nub_clr   = 0.2;      // covers button, plate and lid play together
peg_d     = 2.0;
peg_hole  = 2.2;
ledge_d   = 3;        // plate corbels and the rear ledge: depth from the wall
ledge_flat = 0.5;     // vertical face under the plate, then 45 degrees into the wall

/* [Status LED] */
led_from_left = 13;   // LED centre from the left wall, between the switch and the XIAO
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
boss_dia_s = 4.4;     // slimmer over the cell, so the plate notches stay small
boss_in   = corner_r - 0.5;     // centre from the outside faces, as close as the countersink allows
boss_deep = 8;
boss_floor = 0.4;     // under the pilot hole of a hanging boss

// ---------------------------------------------------------------- derived
sw_out  = sw_lever_h - swb.z;
sw_p    = [swb.z, swb.x, swb.y];      // lever points -X
leg_below = btn_leg - plate_t;

// The XIAO band only needs the board and its wires; the battery band stacks
// the cell, the matrix wires and the buttons.
cav_h = max(xiao_lift + xiao_top + clr_mod + xiao_wire_h,
            bat.z + clr_mod + matrix_wire_h + btn_leg + btn.z);

bat_pkt = [bat.x + bat_room.x + 2*fit, bat.y + bat_room.y + 2*fit];
inner_w = bat_pkt.x;                  // the side walls hold the cell

// XIAO in the front-right corner: front wall, and the receptacle on the right wall.
xiao_pos = [inner_w - xiao_usb_over - xiao.x, fit];
xiao_x1  = xiao_pos.x + xiao.x;
xiao_y1  = xiao_pos.y + xiao.y;
usb_y = xiao_pos.y + xiao.y/2;
usb_z = xiao_lift + xiao_top - xiao_usb.y/2;     // receptacle centre, cavity coordinates
xiao_stop_x = xiao_pos.x - fit - rib;

// The cell sits right behind the XIAO, up to the back wall.
bat_rib_y = xiao_y1 + xiao_back_clr;
b2_y    = bat_rib_y + rib;
inner_l = b2_y + bat_pkt.y;
bat_pos = [(inner_w - bat.x)/2, b2_y + (bat_pkt.y - bat.y)/2];

out_w  = inner_w + 2*wall;
out_l  = inner_l + 2*wall;
tray_h = floor_t + cav_h;
out_h  = tray_h + top_t;
lid_o  = rim_t + lid_clr;

// bosses: front left, front right, rear left, rear right. A boss with a low
// above 0 hangs: 45 degree ramps off both walls, starting at that height.
boss_d   = [boss_dia, boss_dia, boss_dia_s, boss_dia_s];
boss_c   = boss_in - wall;            // centre from the inner walls
boss_reach = [for (d = boss_d) boss_c + d/2];
xiao_low = max(xiao_lift + xiao_top + clr_mod, usb_z + (xiao_usb.y + 1)/2);
bat_low  = bat.z + clr_mod;
boss_low = [0, xiao_low, bat_low, bat_low];
boss_xy = [[boss_in, boss_in], [out_w-boss_in, boss_in],
           [boss_in, out_l-boss_in], [out_w-boss_in, out_l-boss_in]];
pilot_deep = [for (i = [0:3]) boss_low[i] <= 0 ? boss_deep
              : min(boss_deep, cav_h - boss_low[i] - boss_c - scr_pilot/2 - boss_floor)];
scr_len = top_t + min(pilot_deep);

// Switch body against the left wall, so the lever sticks out as far as it can.
sw_pos  = [sw_fit, boss_reach[0] + fit + rib + sw_fit];
sw_w    = sw_p.x + 2*sw_fit;
sw_stick = sw_out - sw_fit - wall;
led_pos = [led_from_left, led_inset];

pil_top = cav_h - btn.z - plate_t;
plate_x0 = 0.3;          plate_x1 = inner_w - 0.3;
plate_y1 = inner_l - 0.3;
btn_po  = btn.x + 2*btn_hole_clr;
pkt_ow  = btn.x + 2*btn_clr + 2*rib;
btn_c   = [inner_w/2, plate_y1 - rib - btn_pitch - pkt_ow/2];
plate_y0 = btn_c.y - btn_pitch - pkt_ow/2 - rib;
plate_rib_x0 = ledge_d + fit;
plate_rib_w = rib + 0.01;
corbel_y = plate_y0 + plate_lip;
notch   = max(boss_reach[2], boss_reach[3]) + fit;
peg_xy  = [[ledge_d/2, corbel_y + post_t/2], [inner_w - ledge_d/2, corbel_y + post_t/2]];
ledge_low = pil_top - ledge_flat - ledge_d;

pkt_h   = btn.z - pkt_drop;
slot_l  = btn.y + 0.6;

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

// a point at (a, b) from the two walls of an inside corner is inside the cavity
function in_corner(a, b) = a >= cav_r || b >= cav_r || norm([cav_r - a, cav_r - b]) <= cav_r;

echo(str("body ", out_w, " x ", out_l, " x ", out_h));
echo(str("switch lever sticks out ", sw_stick, " mm"));
echo(str("screws up to M2 x ", floor(scr_len)));

// corners
assert(in_corner(xiao_usb_over, xiao_pos.y));                                // XIAO PCB, front-right
assert(in_corner(bat_pos.x, inner_l - bat_pos.y - bat.y));                  // centred cell, rear corners
assert(sqrt(2)*(corner_r - wall - cav_r) + cav_r <= corner_r - rim_t);      // lid recess still clears the cavity corner
assert(sqrt(2)*(corner_r - boss_in) + scr_head/2 + 0.5 <= corner_r - lid_o);   // countersink keeps 0.5 of lid corner
// hanging bosses
assert(boss_reach[1] <= usb_y - xiao_usb.x/2 || xiao_low >= usb_z + xiao_usb.y/2 + clr_mod);
assert(min(pilot_deep) >= 4);
assert(boss_low[1] + boss_reach[1] < cav_h && boss_low[2] + boss_reach[2] < cav_h);
// front band
assert(sw_pos.y - sw_fit - rib >= boss_reach[0] + fit);                     // switch cradle clear of the front-left boss
assert(sw_pos.y + sw_p.y + sw_fit + rib <= bat_rib_y);
assert(sw_stick >= 2.5);
assert(led_pos.x - led_or/2 >= max(boss_reach[0], sw_w + rib) + 1);         // LED clear of the boss and the switch cradle
assert(led_pos.x + led_or/2 + 1 <= xiao_stop_x);                            // and of the XIAO end stops
assert(led_gus >= 1);                                                        // the webs stay off the floor
assert(led_leg_gap <= led_d - 1);
assert(wall + usb_y - 6.5 >= corner_r - 0.5);                               // USB-C relief stays off the rounded corner
assert(xiao_pin_free >= 2);
assert(bat_lead_gap <= xiao_stop_x);                                         // lead gap opens into the switch side
// plate
assert(pil_top >= bat.z + clr_mod + leg_below);
assert(ledge_low >= bat_low);                                                // rear ledge clears the cell
assert(ledge_low >= xiao_low && ledge_d <= xiao_usb_over + xiao_pin1 - 0.4);   // right corbel over the XIAO, clear of D0's wire
assert(ledge_low >= xiao_lift + xiao.z + xiao_wire_h);
assert(plate_y0 >= led_pos.y + led_or/2 + 1);
assert(plate_y0 >= max(boss_reach[0], boss_reach[1]) + fit);
assert(corbel_y + post_t <= b2_y);                                           // corbels in the front band, not over the cell
assert(btn_c.x - btn_pitch - pkt_ow/2 >= notch);                            // grid clear of the plate's corner notches
assert(peg_xy[0].x + peg_hole/2 + 0.4 <= plate_rib_x0);
assert(peg_d/2 + 0.4 <= ledge_d/2);
assert(act_rise < top_t);
assert(dish_min >= 0.4);
assert(cross_w >= 2);
assert(dish_d < btn_pitch);

// ================================================================ helpers
module rrect(sx, sy, r, h)
    linear_extrude(height = h)
        translate([r, r]) offset(r = r) square([sx - 2*r, sy - 2*r]);

// inner-cavity coordinates
module at(x, y, z = 0) translate([wall + x, wall + y, floor_t + z]) children();

module cavity(extra = 0, drop = 0, grow = 0)
    translate([wall - grow, wall - grow, floor_t - drop])
        rrect(inner_w + 2*grow, inner_l + 2*grow, cav_r + grow, cav_h + extra + drop);

module each_key()
    for (i = [-1:1], j = [-1:1])
        translate([btn_c.x + i*btn_pitch, btn_c.y + j*btn_pitch]) children();

// Plate support off a wall, at the origin: the wall is the YZ plane, the
// support goes +X and runs len along +Y. Flat top at pil_top, 45 degrees below.
module wall_ledge(len)
    translate([0, len, floor_t + pil_top]) rotate([90, 0, 0]) linear_extrude(height = len)
        polygon([[-weld, 0], [ledge_d, 0], [ledge_d, -ledge_flat],
                 [0, -ledge_flat - ledge_d], [-weld, -ledge_flat - ledge_d - weld]]);

// =================================================================== tray
module usbc_cut() {
    y = wall + usb_y;
    z = floor_t + usb_z;
    translate([out_w - wall - 1, y - (xiao_usb.x + 1)/2, z - (xiao_usb.y + 1)/2])
        cube([wall + 2, xiao_usb.x + 1, xiao_usb.y + 1]);
    translate([out_w - 0.8, y - 6.5, z - 3.1]) cube([1.8, 13, 6.2]);      // overmould relief
}

module switch_cut() {
    y = wall + sw_pos.y + sw_p.y/2;
    z = floor_t + sw_p.z/2;
    translate([-1, y - sw_slot_w/2, z - 1.5]) cube([wall + 2, sw_slot_w, 3]);
}

// The board rests on a rail under its USB end, which takes the plug's push,
// and two corner pads under the far end. The BAT and SWD wires leave under the
// far end, towards the switch, so the board can sit right against the battery
// rib. Front wall, battery rib and two corner stops hold it; a dab of hot glue
// on the supports keeps it down.
module xiao_cradle() {
    x0 = xiao_pos.x; y0 = xiao_pos.y;
    pad = xiao_pin_free - 0.5;
    h = xiao_lift + xiao.z;
    at(xiao_x1 - pad, y0, -weld) cube([pad, xiao.y, xiao_lift + weld]);
    for (y = [y0, xiao_y1 - pad])
        at(x0, y, -weld) cube([pad, pad, xiao_lift + weld]);
    at(xiao_stop_x, -weld, -weld) cube([rib, y0 + pad + weld, h + weld]);
    at(xiao_stop_x, xiao_y1 - pad, -weld) cube([rib, bat_rib_y - xiao_y1 + pad + weld, h + weld]);
}

// The left wall stops the body; the pins leave through the back rib's middle.
module switch_cradle() {
    oy = sw_pos.y - sw_fit;
    d = sw_p.y + 2*sw_fit;  h = sw_p.z + weld;
    for (y = [oy - rib, oy + d])
        at(-weld, y, -weld) cube([sw_w + rib + weld, rib, h]);
    for (y = [oy - rib, oy + d - sw_stub])
        at(sw_w, y, -weld) cube([rib, rib + sw_stub, h]);
}

// The hull fills the corner behind the boss, so a slim boss is still tied to the
// walls. A hanging boss is that column from boss_low + its reach up, plus a 45
// degree ramp off each wall down to boss_low.
module boss(i) {
    p = boss_xy[i]; d = boss_d[i]; low = boss_low[i];
    c = [p.x < out_w/2 ? 0 : out_w, p.y < out_l/2 ? 0 : out_l];
    z1 = low <= 0 ? floor_t - weld : floor_t + low + boss_reach[i];
    xr = c.x == 0 ? [0, wall + boss_reach[i]] : [out_w - wall - boss_reach[i], out_w];
    yr = c.y == 0 ? [0, wall + boss_reach[i]] : [out_l - wall - boss_reach[i], out_l];
    xw = c.x == 0 ? wall - weld : out_w - wall;
    yw = c.y == 0 ? wall - weld : out_l - wall;
    strips = [[[xw, yr[0]], [weld, yr[1] - yr[0]]], [[xr[0], yw], [xr[1] - xr[0], weld]]];
    intersection() {
        if (low <= 0)
            hull() {
                translate([p.x, p.y, z1]) cylinder(d = d, h = tray_h - z1);
                translate([min(p.x, c.x), min(p.y, c.y), z1])
                    cube([abs(p.x - c.x), abs(p.y - c.y), tray_h - z1]);
            }
        else
            for (s = strips) hull() {
                translate([p.x, p.y, z1]) cylinder(d = d, h = tray_h - z1);
                translate([min(p.x, c.x), min(p.y, c.y), z1])
                    cube([abs(p.x - c.x), abs(p.y - c.y), tray_h - z1]);
                translate([s[0].x, s[0].y, floor_t + low]) cube([s[1].x, s[1].y, 0.01]);
            }
        cavity(0, weld);
    }
}

// Plate supports: a corbel on each side wall in the front band, with the pegs,
// and a ledge along the back wall over the cell.
module plate_supports() {
    intersection() {
        union() {
            translate([wall, wall + corbel_y, 0]) wall_ledge(post_t);
            translate([wall + inner_w, wall + corbel_y, 0]) mirror([1, 0, 0]) wall_ledge(post_t);
            translate([wall, wall + inner_l, 0]) rotate([0, 0, -90]) wall_ledge(inner_w);
        }
        cavity(0, weld);
    }
    for (p = peg_xy) at(p.x, p.y, pil_top) cylinder(d = peg_d, h = 1.0);
}

// Front stop for the cell, also the XIAO's back stop. It leaves the left end
// open for the leads.
module battery_rib()
    at(bat_lead_gap, bat_rib_y, -weld) cube([inner_w - bat_lead_gap + weld, rib, bat.z + weld]);

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
            for (i = [0:3]) boss(i);
            xiao_cradle();
            switch_cradle();
            plate_supports();
            battery_rib();
            led_bracket();
        }
        for (i = [0:3])
            translate([boss_xy[i].x, boss_xy[i].y, tray_h - pilot_deep[i]])
                cylinder(d = scr_pilot, h = pilot_deep[i] + 1);
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
        for (x = [-1, inner_w - notch])
            translate([x, inner_l - notch, -1]) cube([notch + 1, notch + 1, plate_t + pkt_h + 2]);
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

// USB-C towards +X; D0-D6 along the back edge, D0 at the USB end
module xiao_mock() {
    cube(xiao);
    translate([xiao.x + xiao_usb_over - 7.3, (xiao.y - xiao_usb.x)/2, xiao_top - xiao_usb.y])
        cube([7.3, xiao_usb.x, xiao_usb.y]);
    translate([3, 3, xiao.z]) cube([10, 10, 1.5]);          // module can
}

module switch_mock() {
    cube(sw_p);
    translate([-sw_out, sw_p.y/2 - 1, sw_p.z/2 - 1.2]) cube([sw_out, 2, 2.4]);
}

module mock() {
    %at(bat_pos.x, bat_pos.y) cube(bat);
    %at(xiao_pos.x, xiao_pos.y, xiao_lift) xiao_mock();
    %at(sw_pos.x, sw_pos.y) switch_mock();
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
