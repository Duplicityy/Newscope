package io.ruin.content.content242.bosses;

import io.ruin.model.stat.Stat;

/** Instance-owned125 Defence floor; a blocked drain cannot spill into BGS's later stats. */
final class MuspahDefence242 extends Stat {
    MuspahDefence242(int base,int current){super(base);currentLevel=Math.max(125,current);}
    @Override public int alter(int level){return super.alter(Math.max(125,level));}
    @Override public int drain(int amount){super.drain(amount);return Math.max(0,amount);}
}
