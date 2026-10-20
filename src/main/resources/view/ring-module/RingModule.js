import { KnockoutArt } from './KnockoutArt.js'

/* global PIXI */

// Draws the whole game on one canvas with KnockoutArt (the same art as knockout/viewer.html) and
// shows it as a PIXI sprite. Java sends one snapshot per turn (RingModule.java); this module
// interpolates between them: frame k is the state after turn k, frame 0 the start.
// Turn mode in frame k describes the action over turn k (from frame k-1 to k).

const TPS = 8 // effects are timed in seconds at 1x, where one turn is 125 ms (setFrameDuration)
const IMPACT_FX = 0.18
const SKID_MIN = 40
const SKID_FADE = 24

// Viewer settings, shown in the player's settings menu through `options` in config.js.
// Remembered per browser (localStorage, which can be unavailable: then they just reset).
function load (key, fallback) {
  try { const v = localStorage.getItem('knockout.' + key); return v === null ? fallback : v === '1' } catch (e) { return fallback }
}
function save (key, value) {
  try { localStorage.setItem('knockout.' + key, value ? '1' : '0') } catch (e) {}
}
export const settings = {
  debug: load('debug', false),
  dark: load('dark', false)
}
export function setSetting (key, value) {
  settings[key] = value
  save(key, value)
  if (RingModule.instance) RingModule.instance.redraw()
}

// Side panels, one per player (shobu-style): avatar, name, units left, a roster of the
// player's units with stamina, and the turn (left) / ring radius (right).
const PANEL_W = 390
const PANEL_Y = 40
const PANEL_H = 1000
const AVATAR = 130
const panelX = (p, W) => (p === 0 ? 30 : W - 30 - PANEL_W)

const lerp = (a, b, u) => a + (b - a) * u
function lerpAngle (a, b, u) {
  let d = b - a
  while (d > Math.PI) d -= 2 * Math.PI
  while (d < -Math.PI) d += 2 * Math.PI
  return a + d * u
}

export class RingModule {
  constructor () {
    RingModule.instance = this
    this.last = null
    this.frames = []
    this.cfg = null
    this.players = []
    this.facing = [] // per frame: unit index -> world angle
    this.impactsByUnit = {}
    this.koMarks = []
    this.skids = []
    this.events = []
  }

  static get moduleName () {
    return 'ring'
  }

  handleGlobalData (players, globalData) {
    this.players = players
    this.cfg = globalData
  }

  handleFrameData (frameInfo, raw) {
    const f = this.frames.length
    const frame = raw ? { ...raw, index: f } : { ...this.frames[f - 1], i: [], m: [], index: f }
    this.frames.push(frame)
    const prev = f > 0 ? this.frames[f - 1] : null
    const prevFacing = f > 0 ? this.facing[f - 1] : null
    const facing = []
    frame.u.forEach((u, k) => {
      const ax = u[9] || 0
      const ay = u[10] || 0
      if (Math.hypot(ax, ay) > 1) facing[k] = Math.atan2(ay, ax)
      else facing[k] = prevFacing ? prevFacing[k] : Math.atan2(-u[3], -u[2]) // start facing the center
    })
    this.facing.push(facing)
    if (!prev) return frame
    const cfg = this.cfg
    for (const [frac, x, y, nx, ny, ia, ib, speed] of (frame.i || [])) {
      const time = (f - 1 + frac) / TPS
      const angle = Math.atan2(-ny, nx)
      const strength = Math.max(0, Math.min(1, (speed - cfg.impact_min_speed) / 450))
      for (const id of [ia, ib]) (this.impactsByUnit[id] = this.impactsByUnit[id] || []).push({ time, angle, strength })
      if (speed >= 250) this.events.push({ kind: 'impact', time, x, y, angle, strength })
    }
    frame.u.forEach((b, k) => {
      const a = prev.u[k]
      if (!a[4]) return
      if (!b[4]) {
        this.koMarks.push({ time: f / TPS, x: b[2], y: b[3], team: b[1] })
        this.events.push({ kind: 'knockout', time: f / TPS, u: b })
      }
      if (b[5] === 'PLANT' && a[5] !== 'PLANT') this.events.push({ kind: 'dust', time: (f - 1) / TPS, u: a })
      if (b[5] === 'PLANT' && Math.hypot(b[2] - a[2], b[3] - a[3]) >= SKID_MIN) {
        this.skids.push({ f, x0: a[2], y0: a[3], x1: b[2], y1: b[3] })
        this.events.push({ kind: 'dust', time: (f - 0.5) / TPS, u: [a[0], a[1], (a[2] + b[2]) / 2, (a[3] + b[3]) / 2] })
      }
    })
    return frame
  }

  reinitScene (container, canvasData) {
    // The stage is in world units (1920 x 1080) whatever the screen size; draw at that size.
    this.W = 1920
    this.H = 1080
    this.canvas = document.createElement('canvas')
    this.canvas.width = this.W
    this.canvas.height = this.H
    this.ctx = this.canvas.getContext('2d')
    this.texture = PIXI.Texture.fromCanvas ? PIXI.Texture.fromCanvas(this.canvas) : PIXI.Texture.from(this.canvas)
    this.sprite = new PIXI.Sprite(this.texture)
    this.sprite.width = this.W
    this.sprite.height = this.H
    container.addChild(this.sprite)
    for (const p of [0, 1]) {
      const player = this.players[p]
      if (!player || !player.avatar) continue
      const a = new PIXI.Sprite(player.avatar)
      a.x = panelX(p, this.W) + 20
      a.y = PANEL_Y + 20
      a.width = AVATAR
      a.height = AVATAR
      container.addChild(a)
    }
  }

  updateScene (previousData, currentData, progress) {
    if (!this.ctx || !currentData || !this.cfg) return
    const fb = currentData
    const fa = previousData && previousData.index === fb.index - 1 ? previousData : fb
    const u = fa === fb ? 1 : progress
    this.last = [fa, fb, u]
    this.redraw()
  }

  /** Draw the last scene again: a setting changed while the replay is paused. */
  redraw () {
    if (!this.ctx || !this.last) return
    this.draw(...this.last)
    if (this.texture.baseTexture && this.texture.baseTexture.update) this.texture.baseTexture.update()
    else if (this.texture.update) this.texture.update()
  }

  draw (fa, fb, u) {
    const { ctx, W, H, cfg } = this
    const S = H / (2 * cfg.arena_r * 1.06)
    const X = x => W / 2 + x * S
    const Y = y => H / 2 - y * S
    const R = cfg.unit_r * S
    const a = fa.index
    const tt = a + (fa === fb ? 0 : u)
    const dark = settings.dark
    ctx.clearRect(0, 0, W, H)
    if (settings.debug) {
      this.drawDebug(fa, fb, u, X, Y, S, R, tt)
      this.drawHud(fa, fb, u)
      return
    }
    KnockoutArt.arena(ctx, { x: X(0), y: Y(0), r: lerp(fa.r, fb.r, u) * S, originalR: cfg.arena_r * S, dark, t: tt / TPS, shrinking: fb.r < fa.r })

    for (const sk of this.skids) { // skid marks under everything
      if (tt < sk.f - 1) continue
      const age = tt - sk.f
      if (age > SKID_FADE) continue
      const q = Math.min(1, tt - (sk.f - 1))
      KnockoutArt.skid(ctx, { x0: X(sk.x0), y0: Y(sk.y0), x1: X(lerp(sk.x0, sk.x1, q)), y1: Y(lerp(sk.y0, sk.y1, q)), r: R, dark, alpha: 0.45 * Math.min(1, 1 - age / SKID_FADE) })
    }
    for (const m of this.koMarks) {
      if (tt / TPS >= m.time) KnockoutArt.koMarker(ctx, { x: X(m.x), y: Y(m.y), r: R, team: m.team, alpha: 0.85 })
    }

    const units = []
    fa.u.forEach((ua, k) => {
      if (!ua[4]) return
      const ub = fb.u[k]
      units.push({
        id: ua[0],
        owner: ua[1],
        x: lerp(ua[2], ub[2], u),
        y: lerp(ua[3], ub[3], u),
        st: lerp(ua[6], ub[6], u),
        vx: lerp(ua[7] || 0, ub[7] || 0, u),
        vy: lerp(ua[8] || 0, ub[8] || 0, u),
        mode: fa === fb ? '' : ub[5],
        ang: lerpAngle(this.facing[a][k], this.facing[fb.index][k], Math.min(1, u / 0.3)),
        alpha: ub[4] ? 1 : 1 - u
      })
    })
    for (const w of units) { // ground effects first, so a trail never covers another face
      KnockoutArt.ground(ctx, { x: X(w.x), y: Y(w.y), r: R, angle: -w.ang, team: w.owner, mode: w.mode, alpha: w.alpha, vx: w.vx * S, vy: -w.vy * S })
    }
    for (const w of units) {
      ctx.globalAlpha = w.alpha
      KnockoutArt.unit(ctx, { x: X(w.x), y: Y(w.y), r: R, angle: -w.ang, team: w.owner, mode: w.mode, stamina: w.st / cfg.stamina_max, deform: this.deform(w, tt, u) })
      ctx.globalAlpha = 1
    }
    for (const e of this.events) {
      const age = tt / TPS - e.time
      if (age < 0 || age > (e.kind === 'impact' ? IMPACT_FX : 0.35)) continue
      if (e.kind === 'impact') KnockoutArt.impact(ctx, { x: X(e.x), y: Y(e.y), r: R, angle: e.angle, strength: e.strength, age })
      else KnockoutArt[e.kind](ctx, { x: X(e.u[2]), y: Y(e.u[3]), r: R, team: e.u[1], dark, age })
    }
    this.drawMessages(fb, units, X, Y, R)
    this.drawHud(fa, fb, u)
  }

  /** Debug view: plain ring, velocity x2 (green) and thrust x5 (orange) arrows, numbers. */
  drawDebug (fa, fb, u, X, Y, S, R, tt) {
    const { ctx, W, H, cfg } = this
    const dark = settings.dark
    const fg = dark ? '#e8e8ec' : '#1d1d1f'
    const grid = dark ? '#2c3038' : '#e3e0d8'
    ctx.fillStyle = dark ? '#16191e' : '#fbfaf7'
    ctx.fillRect(0, 0, W, H)
    ctx.strokeStyle = grid
    ctx.lineWidth = 1
    for (let g = -6000; g <= 6000; g += 1000) {
      ctx.beginPath(); ctx.moveTo(X(g), 0); ctx.lineTo(X(g), H); ctx.stroke()
      ctx.beginPath(); ctx.moveTo(0, Y(g)); ctx.lineTo(W, Y(g)); ctx.stroke()
    }
    ctx.setLineDash([8, 8])
    ctx.strokeStyle = dark ? '#5d6470' : '#a59f92'
    ctx.beginPath(); ctx.arc(X(0), Y(0), cfg.arena_r * S, 0, 7); ctx.stroke()
    ctx.setLineDash([])
    ctx.strokeStyle = fg
    ctx.lineWidth = 3
    ctx.beginPath(); ctx.arc(X(0), Y(0), lerp(fa.r, fb.r, u) * S, 0, 7); ctx.stroke()
    const arrow = (x0, y0, dx, dy, col) => {
      const len = Math.hypot(dx, dy)
      if (len < 3) return
      const a = Math.atan2(dy, dx)
      const h = Math.min(14, len * 0.4)
      ctx.strokeStyle = col; ctx.fillStyle = col; ctx.lineWidth = 3
      ctx.beginPath(); ctx.moveTo(x0, y0); ctx.lineTo(x0 + dx - Math.cos(a) * h * 0.6, y0 + dy - Math.sin(a) * h * 0.6); ctx.stroke()
      ctx.beginPath(); ctx.moveTo(x0 + dx, y0 + dy)
      ctx.lineTo(x0 + dx - Math.cos(a - 0.4) * h, y0 + dy - Math.sin(a - 0.4) * h)
      ctx.lineTo(x0 + dx - Math.cos(a + 0.4) * h, y0 + dy - Math.sin(a + 0.4) * h)
      ctx.closePath(); ctx.fill()
    }
    for (const m of this.koMarks) {
      if (tt / TPS >= m.time) KnockoutArt.koMarker(ctx, { x: X(m.x), y: Y(m.y), r: R, team: m.team, alpha: 0.6 })
    }
    fa.u.forEach((ua, k) => {
      if (!ua[4]) return
      const ub = fb.u[k]
      const x = X(lerp(ua[2], ub[2], u))
      const y = Y(lerp(ua[3], ub[3], u))
      const vx = lerp(ua[7] || 0, ub[7] || 0, u)
      const vy = lerp(ua[8] || 0, ub[8] || 0, u)
      const st = lerp(ua[6], ub[6], u)
      const mode = fa === fb ? '' : ub[5]
      const team = KnockoutArt.teams[ua[1]].main
      const alpha = ub[4] ? 1 : 1 - u
      ctx.globalAlpha = alpha * 0.3
      ctx.fillStyle = team
      ctx.beginPath(); ctx.arc(x, y, R, 0, 7); ctx.fill()
      ctx.globalAlpha = alpha
      ctx.strokeStyle = team; ctx.lineWidth = mode === 'PLANT' ? 6 : 2
      ctx.setLineDash(mode === 'DASH' ? [10, 6] : [])
      ctx.beginPath(); ctx.arc(x, y, R, 0, 7); ctx.stroke()
      ctx.setLineDash([])
      arrow(x, y, vx * 2 * S, -vy * 2 * S, '#1f9d55')
      arrow(x, y, (ub[9] || 0) * 5 * S, -(ub[10] || 0) * 5 * S, '#e08a00')
      ctx.fillStyle = fg; ctx.textAlign = 'center'; ctx.textBaseline = 'middle'
      ctx.font = 'bold 22px monospace'
      ctx.fillText('#' + ua[0], x, y - 14)
      ctx.font = '18px monospace'
      ctx.fillText('st ' + Math.round(st), x, y + 6)
      ctx.fillText('v ' + Math.round(Math.hypot(vx, vy)), x, y + 24)
      if (mode) ctx.fillText(mode, x, y + 42)
      ctx.globalAlpha = 1
    })
    ctx.textAlign = 'left'; ctx.textBaseline = 'bottom'; ctx.font = '22px sans-serif'
    ctx.fillStyle = '#1f9d55'; ctx.fillText('velocity x2', 40, H - 72)
    ctx.fillStyle = '#e08a00'; ctx.fillText('thrust x5', 40, H - 44)
    ctx.fillStyle = fg; ctx.fillText('thick ring = PLANT, dashed = DASH', 200, H - 44)
  }

  deform (w, tt, u) {
    const out = []
    if (w.mode === 'DASH') { // stretch along travel, peaking mid-turn
      const sp = Math.hypot(w.vx, w.vy)
      out.push([sp > 1 ? Math.atan2(-w.vy, w.vx) : -w.ang, 0.24 * Math.sin(Math.PI * u)])
    }
    for (const h of this.impactsByUnit[w.id] || []) { // squash along the contact normal
      const age = tt / TPS - h.time
      if (age >= 0 && age < IMPACT_FX) out.push([h.angle, -(0.08 + 0.17 * h.strength) * (1 - age / IMPACT_FX)])
    }
    return out
  }

  drawMessages (frame, units, X, Y, R) {
    const ctx = this.ctx
    ctx.font = `bold ${Math.round(R * 0.55)}px sans-serif`
    ctx.textAlign = 'center'
    ctx.textBaseline = 'bottom'
    for (const [id, text] of frame.m || []) {
      const w = units.find(v => v.id === id)
      if (!w) continue
      ctx.lineWidth = 4
      ctx.strokeStyle = 'rgba(255,255,255,0.85)'
      ctx.strokeText(text, X(w.x), Y(w.y) - R * 1.15)
      ctx.fillStyle = '#22303a'
      ctx.fillText(text, X(w.x), Y(w.y) - R * 1.15)
    }
  }

  drawHud (fa, fb, u) {
    const { ctx, W, cfg } = this
    const dark = settings.dark
    const ink = dark ? '#ece6d8' : '#2a2f36'
    const muted = dark ? '#9aa3ad' : '#857c6c'
    const rule = dark ? 'rgba(255,255,255,0.10)' : 'rgba(60,45,20,0.12)'
    const track = dark ? '#33414d' : '#e8dfcc'
    const roundRect = (x, y, w, h, r) => {
      ctx.beginPath()
      ctx.moveTo(x + r, y); ctx.arcTo(x + w, y, x + w, y + h, r); ctx.arcTo(x + w, y + h, x, y + h, r)
      ctx.arcTo(x, y + h, x, y, r); ctx.arcTo(x, y, x + w, y, r); ctx.closePath()
    }
    const bar = (x, y, w, h, frac, color) => {
      roundRect(x, y, w, h, h / 2); ctx.fillStyle = track; ctx.fill()
      const f = Math.max(0, Math.min(1, frac))
      if (f > 0) { roundRect(x, y, Math.max(h, w * f), h, h / 2); ctx.fillStyle = color; ctx.fill() }
    }
    const frame = u < 0.5 ? fa : fb
    const HEAD = 190                  // header height (avatar, name, count)
    const FOOT = 210                  // footer height (turn / ring)
    for (const p of [0, 1]) {
      const team = KnockoutArt.teams[p]
      const x0 = panelX(p, W)
      const y0 = PANEL_Y
      const mid = x0 + PANEL_W / 2
      // panel
      roundRect(x0, y0, PANEL_W, PANEL_H, 22)
      ctx.fillStyle = dark ? 'rgba(24,34,43,0.95)' : 'rgba(255,251,243,0.95)'
      ctx.fill()
      ctx.lineWidth = 5
      ctx.strokeStyle = team.main
      ctx.stroke()
      // header: avatar frame (the avatar is a PIXI sprite drawn over the canvas), name, count
      ctx.fillStyle = dark ? '#2b3a46' : '#efe6d2'
      roundRect(x0 + 20, y0 + 20, AVATAR, AVATAR, 10); ctx.fill()
      ctx.strokeStyle = team.main; ctx.lineWidth = 3; ctx.stroke()
      const name = this.players[p] ? this.players[p].name : 'Player ' + (p + 1)
      const tx = x0 + 170
      ctx.textAlign = 'left'
      ctx.textBaseline = 'middle'
      ctx.fillStyle = dark ? team.shine : team.shade
      let size = 38
      do { ctx.font = `bold ${size}px sans-serif`; size -= 2 } while (ctx.measureText(name).width > PANEL_W - 190 && size > 18)
      ctx.fillText(name, tx, y0 + 46)
      const alive = frame.u.filter(v => v[1] === p && v[4]).length
      const cy = y0 + 112              // number and label share this center line
      ctx.fillStyle = team.main
      ctx.font = 'bold 76px sans-serif'
      ctx.fillText(String(alive), tx, cy + 4)
      const numW = ctx.measureText(String(alive)).width
      ctx.fillStyle = muted
      ctx.font = '26px sans-serif'
      ctx.fillText(alive === 1 ? 'unit left' : 'units left', tx + numW + 14, cy)
      // dividers
      ctx.strokeStyle = rule; ctx.lineWidth = 2
      for (const yy of [y0 + HEAD, y0 + PANEL_H - FOOT]) {
        ctx.beginPath(); ctx.moveTo(x0 + 24, yy); ctx.lineTo(x0 + PANEL_W - 24, yy); ctx.stroke()
      }
      // roster: 2 rows x 3, centered between the dividers
      const roster = fb.u.map((v, k) => [v, k]).filter(([v]) => v[1] === p)
      const top = y0 + HEAD
      const bottom = y0 + PANEL_H - FOOT
      const rowH = 230
      const rows = Math.ceil(roster.length / 3)
      const firstCy = (top + bottom) / 2 - (rows - 1) * rowH / 2 - 22
      roster.forEach(([vb, k], i) => {
        const va = fa.u[k]
        const col = i % 3
        const row = Math.floor(i / 3)
        const ux = x0 + PANEL_W / 2 + (col - 1) * 118
        const uy = firstCy + row * rowH
        const out = !va[4] || (!vb[4] && u >= 1)
        const st = lerp(va[6], vb[6], u) / cfg.stamina_max
        ctx.globalAlpha = out ? 0.22 : 1
        KnockoutArt.unit(ctx, { x: ux, y: uy, r: 46, angle: p === 0 ? 0 : Math.PI, team: p, mode: vb[4] ? vb[5] : '', stamina: st })
        ctx.globalAlpha = 1
        if (out) KnockoutArt.koMarker(ctx, { x: ux, y: uy, r: 76, team: p, alpha: 0.9 })
        bar(ux - 46, uy + 62, 92, 12, out ? 0 : st, st >= 0.4 ? team.main : '#e0a33a')
        ctx.fillStyle = muted
        ctx.font = '20px sans-serif'
        ctx.textAlign = 'center'
        ctx.textBaseline = 'top'
        ctx.fillText('#' + vb[0], ux, uy + 82)
      })
      // footer: turn (left) / ring radius (right), each with a progress bar
      const fy = y0 + PANEL_H - FOOT
      ctx.textAlign = 'center'
      ctx.textBaseline = 'middle'
      ctx.fillStyle = muted
      ctx.font = 'bold 26px sans-serif'
      const maxT = cfg.max_turns || 225
      if (p === 0) {
        ctx.fillText('TURN', mid, fy + 46)
        ctx.fillStyle = ink
        ctx.font = 'bold 58px sans-serif'
        ctx.fillText(`${frame.t} / ${maxT}`, mid, fy + 104)
        bar(x0 + 40, fy + 150, PANEL_W - 80, 12, lerp(fa.t, fb.t, u) / maxT, dark ? '#c9bfa8' : '#8a7f6c')
      } else {
        const r = lerp(fa.r, fb.r, u)
        ctx.fillText('RING', mid, fy + 46)
        ctx.fillStyle = ink
        ctx.font = 'bold 58px sans-serif'
        ctx.fillText(String(Math.round(r)), mid, fy + 104)
        // how much of the ring is left: full bar = 5000, empty = the smallest ring (500)
        const minR = 500
        const shrinking = fb.r < fa.r || frame.r < cfg.arena_r
        bar(x0 + 40, fy + 150, PANEL_W - 80, 12, (r - minR) / (cfg.arena_r - minR), shrinking ? (dark ? '#ffb46b' : '#c4572f') : (dark ? '#c9bfa8' : '#8a7f6c'))
        if (shrinking) {
          ctx.fillStyle = dark ? '#ffd694' : '#9c492b'
          ctx.font = '22px sans-serif'
          ctx.fillText(frame.r <= minR ? 'at its smallest' : 'shrinking', mid, fy + 184)
        }
      }
      ctx.textAlign = 'left'
      ctx.textBaseline = 'alphabetic'
    }
  }
}
