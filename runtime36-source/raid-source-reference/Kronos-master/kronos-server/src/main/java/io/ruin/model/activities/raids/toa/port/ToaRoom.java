package io.ruin.model.activities.raids.toa.port;

/** Source coordinates pinned from Near Reality EncounterType; no arbitrary substitute maps. */
public enum ToaRoom {
    MAIN_HALL("Main hall",14160,3550,5161,0),
    CRONDIS("Crondis puzzle",15698,3943,5280,0),
    ZEBAK("Zebak",15700,3941,5408,0),
    SCABARAS("Scabaras puzzle",14162,3522,5279,0),
    KEPHRI("Kephri",14164,3544,5408,0),
    HET("Het puzzle",14674,3667,5280,0),
    AKKHA("Akkha",14676,3689,5408,1),
    APMEKEN("Apmeken puzzle",15186,3814,5280,0),
    BABA("Ba-ba",15188,3800,5408,0),
    WARDENS("Wardens",15184,3808,5166,1),
    WARDENS_FINAL("Wardens final",15696,3936,5157,1),
    REWARDS("Burial tomb",14672,3680,5170,0);
    public final String title;
    public final int sourceRegion,entryX,entryY,plane,baseX,baseY;
    ToaRoom(String title,int region,int x,int y,int plane){
        this.title=title;sourceRegion=region;entryX=x;entryY=y;this.plane=plane;
        baseX=(region>>8)*64;baseY=(region&255)*64;
    }
}
