/* Knockout art: procedural canvas drawing, no external assets. Shared with knockout/viewer.html
 * (the prototype replay viewer); keep the two in sync. Coordinates are canvas pixels. */
export const KnockoutArt = (() => {
  const TAU = Math.PI * 2, clamp = x => Math.max(0, Math.min(1, x));
  const themes = {
    light: {out:'#e8e2d5', sand:'#f1dfb7', edge:'#dfc494', ink:'#29323b', rope:'#92734d', dust:'#b39b76', ghost:'#c8bcaa'},
    dark:  {out:'#18242d', sand:'#74674e', edge:'#60523e', ink:'#17232d', rope:'#c3a776', dust:'#dfc79e', ghost:'#394852'}
  };
  // player 1 coral, player 2 azure: the same order as CodinGame's player colors (red, blue)
  const teams = [{main:'#ed855b', shade:'#b4523e', shine:'#ffd0a0'},
                 {main:'#378ed0', shade:'#236294', shine:'#a4d9ed'}];
  // Linear mix of two #rrggbb colors.
  function mix(a,b,t) {
    const p=h=>[1,3,5].map(i=>parseInt(h.slice(i,i+2),16)),A=p(a),B=p(b);
    return '#'+A.map((v,i)=>Math.round(v+(B[i]-v)*t).toString(16).padStart(2,'0')).join('');
  }
  function circle(c,x,y,r,fill,stroke,w=1) {
    c.beginPath(); c.arc(x,y,Math.max(0,r),0,TAU);
    if(fill){c.fillStyle=fill;c.fill();}
    if(stroke){c.strokeStyle=stroke;c.lineWidth=w;c.stroke();}
  }
  function line(c,points,color,w) {
    c.beginPath();points.forEach(([x,y],i)=>i?c.lineTo(x,y):c.moveTo(x,y));
    c.strokeStyle=color;c.lineWidth=w;c.stroke();
  }
  // Independent face layer: local +x is forward, body radius = 1.
  function face(c, mode='') {
    c.save();c.lineCap='round';c.lineJoin='round';
    for(const s of [-1,1]) {
      c.fillStyle='#fff6dd';c.beginPath();c.ellipse(.37,s*.32,.235,.23,0,0,TAU);c.fill();
      circle(c,.45,s*.32,.09,'#202d37');
    }
    c.restore();
  }
  // ground=true draws under all bodies; draw in a separate pass in dense scrums.
  function ground(c,o) {
    const {x,y,r,mode='',angle=0,vx=0,vy=0,alpha=1,team=0}=o;
    const p=o.tint||teams[team?1:0];
    c.save();c.globalAlpha*=alpha;c.translate(x,y);c.scale(r,r);c.lineCap='round';
    c.fillStyle='#101e2b26';c.beginPath();c.ellipse(.06,.13,1.04,.96,0,0,TAU);c.fill();
    if(mode==='DASH') {
      // Streaks trail the THRUST direction (where the dash pushes), like an exhaust.
      c.rotate(angle);
      for(const [s,len] of [[-.48,.45],[0,.7],[.48,.45]])line(c,[[-1.06,s],[-1.25-len,s]],p.main,.085);
    }
    if(mode==='PLANT') {
      // Four fixed, square grip pads: not a second stamina ring.
      for(let j=0;j<4;j++) {
        c.save();c.rotate(Math.PI/4+j*Math.PI/2);
        line(c,[[1.08,-.20],[1.08,.20]],'#fff1cd',.20);
        line(c,[[1.08,-.18],[1.08,.18]],'#29323b',.10);c.restore();
      }
    }
    c.restore();
  }
  function unit(c,o) {
    const {x,y,r,angle=0,team=0,mode='',stamina=1,alpha=1,deform=[]}=o;
    const p=o.tint||teams[team?1:0], st=clamp(stamina);
    c.save();c.globalAlpha*=alpha;c.translate(x,y);c.scale(r,r);
    // Area-preserving squash/stretch: [screen angle, amount]; + stretches along the axis, - squashes.
    c.save();
    for(const [a,k] of deform){const f=Math.max(.6,1+k);c.rotate(a);c.scale(f,1/f);c.rotate(-a);}
    circle(c,0,0,.98,p.shade,'#22303a',.055);
    circle(c,-.035,-.065,.89,p.main);
    c.save();c.beginPath();c.arc(0,0,.92,0,TAU);c.clip();
    c.fillStyle=p.shine;c.globalAlpha*=.35;
    c.beginPath();c.ellipse(-.25,-.53,.55,.20,-.3,0,TAU);c.fill();c.restore();
    c.save();c.rotate(angle);
    // Stamina meter on the RIM at the back, opposite the eyes: anything inside the body
    // opposite the eyes reads as a mouth. Fills symmetrically from the middle of the back.
    // Sits inside the outline (radii .85-.95), dark track, fill just lighter than the body.
    const half=1.15;
    c.beginPath();c.arc(0,0,.9,Math.PI-half,Math.PI+half);
    c.strokeStyle=mix(p.shade,'#22303a',.55);c.lineWidth=.1;c.lineCap='butt';c.stroke();
    if(st>0){c.beginPath();c.arc(0,0,.9,Math.PI-half*st,Math.PI+half*st);
      c.strokeStyle=mix(p.main,p.shine,.3);c.lineWidth=.1;c.stroke();}
    face(c,mode);
    // Sweat bead when low on stamina: placed beside the head (rotates with facing) but drawn
    // upright on screen, tip up, so it always reads as a falling drop.
    const lowSt=st<.25;
    c.restore();
    if(lowSt){
      const lx=.05,ly=-.66,dx=Math.cos(angle)*lx-Math.sin(angle)*ly,dy=Math.sin(angle)*lx+Math.cos(angle)*ly;
      c.beginPath();c.moveTo(dx,dy-.2);c.quadraticCurveTo(dx+.15,dy+.03,dx,dy+.09);
      c.quadraticCurveTo(dx-.15,dy+.03,dx,dy-.2);
      c.fillStyle='#fff6dd';c.strokeStyle='#22303a';c.lineWidth=.04;c.fill();c.stroke();
    }
    c.restore();
    c.restore();
  }
  // Fixed world-anchored stipple: shrinking clips the sand, never rescales it.
  function arena(c,{x,y,r,originalR,dark=false,shrinking=false,t=0,reducedMotion=false}) {
    const p=themes[dark?'dark':'light'];c.save();
    c.fillStyle=p.out;c.fillRect(0,0,c.canvas.width,c.canvas.height);
    c.translate(x,y);
    circle(c,0,0,originalR,null,p.ghost,1.5);
    circle(c,0,3,r,p.edge);
    const g=c.createRadialGradient(-r*.25,-r*.35,0,0,0,r);
    g.addColorStop(0,p.sand);g.addColorStop(1,p.edge);circle(c,0,0,r,g);
    c.save();c.beginPath();c.arc(0,0,Math.max(0,r-3),0,TAU);c.clip();
    let seed=713;
    for(let j=0;j<480;j++) {
      seed=(Math.imul(seed,1664525)+1013904223)>>>0;const a=seed/4294967296*TAU;
      seed=(Math.imul(seed,1664525)+1013904223)>>>0;const d=Math.sqrt(seed/4294967296)*originalR;
      c.globalAlpha=.16;circle(c,Math.cos(a)*d,Math.sin(a)*d,j%5===0?1.6:.8,p.rope);
    }
    c.restore();
    circle(c,0,0,r,null,p.ink,7);circle(c,0,0,r,null,p.rope,4);
    // Short angled straw stitches, centered exactly on the true boundary.
    const n=Math.max(12,Math.floor(TAU*r/15));
    c.strokeStyle=dark?'#e0c598':'#fff0c9';c.lineWidth=1.5;
    for(let j=0;j<n;j++) {
      const a=j/n*TAU;c.beginPath();
      c.moveTo(Math.cos(a-.003)*(r-2),Math.sin(a-.003)*(r-2));
      c.lineTo(Math.cos(a+.003)*(r+2),Math.sin(a+.003)*(r+2));c.stroke();
    }
    if(shrinking) {
      const pulse=reducedMotion?1:.85+.15*Math.sin(t*TAU);
      c.globalAlpha=pulse;
      for(let j=0;j<12;j++) {
        c.save();c.rotate(j/12*TAU);
        line(c,[[r+13,-4],[r+8,0],[r+13,4]],dark?'#ffd694':'#9c492b',2);c.restore();
      }
    }
    c.restore();
  }
  // Small deterministic one-shot effects. age in replay seconds; no random/time calls.
  function dust(c,{x,y,r,age,dark=false,alpha=1}) {
    if(age<0||age>.28)return;
    const q=age/.28;c.save();c.globalAlpha*=alpha*(1-q)*.45;
    for(let j=0;j<6;j++) {
      const a=j/6*TAU+.3,d=r*(.92+q*.40);
      circle(c,x+Math.cos(a)*d,y+Math.sin(a)*d,r*(.07+.10*q),themes[dark?'dark':'light'].dust);
    }c.restore();
  }
  function impact(c,{x,y,r,angle=0,age,strength=1}) {
    if(age<0||age>.16)return;
    const q=age/.16;c.save();c.translate(x,y);c.rotate(angle);c.globalAlpha*=1-q;
    c.lineCap='round';
    for(const s of [-1,1])for(const d of [-.5,0,.5]) {
      const a=s*Math.PI/2+d,l=r*(.12+.18*clamp(strength));
      const p1=[Math.cos(a)*r*(.12+q*.35),Math.sin(a)*r*(.12+q*.35)];
      const p2=[p1[0]+Math.cos(a)*l,p1[1]+Math.sin(a)*l];
      line(c,[p1,p2],'#29323b',r*.09);line(c,[p1,p2],'#fff3c9',r*.045);
    }c.restore();
  }
  function knockout(c,{x,y,r,age,team=0}) {
    if(age<0||age>.35)return;
    const q=age/.35;c.save();c.globalAlpha*=1-q;
    circle(c,x,y,r*(.8+q*.7),null,teams[team?1:0].main,r*.06);
    for(let j=0;j<5;j++) {
      const a=j/5*TAU,d=r*(.9+q*.8);
      circle(c,x+Math.cos(a)*d,y+Math.sin(a)*d,r*.065,'#fff1cd','#29323b',r*.025);
    }c.restore();
  }
  // Two parallel skid lines along a planted unit's slide; drawn on the ground under everything.
  function skid(c,{x0,y0,x1,y1,r,alpha=1,dark=false}) {
    const dx=x1-x0,dy=y1-y0,d=Math.hypot(dx,dy);if(d<1)return;
    const px=-dy/d*r*.45,py=dx/d*r*.45;c.save();c.globalAlpha*=alpha;c.lineCap='round';
    for(const s of [-1,1])line(c,[[x0+px*s,y0+py*s],[x1+px*s,y1+py*s]],dark?'#2c2519':'#8a7350',r*.13);
    c.restore();
  }
  // Persistent marker where a unit left the ring: a small team-colored cross.
  function koMarker(c,{x,y,r,team=0,alpha=1}) {
    const p=teams[team?1:0],k=r*.32;c.save();c.globalAlpha*=alpha;c.translate(x,y);c.lineCap='round';
    for(const w of [[r*.2,'#22303a'],[r*.11,p.main]])
      for(const s of [-1,1])line(c,[[-k,-k*s],[k,k*s]],w[1],w[0]);
    c.restore();
  }
  return {teams,themes,face,ground,unit,arena,dust,impact,knockout,koMarker,skid};
})();
