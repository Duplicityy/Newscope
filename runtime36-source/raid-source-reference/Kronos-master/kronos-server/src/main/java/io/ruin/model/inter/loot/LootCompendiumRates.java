package io.ruin.model.inter.loot;

import io.ruin.model.item.loot.LootItem;
import io.ruin.model.item.loot.LootTable;
import java.util.*;

/** Selection probabilities from the actual sequential LootTable roller, without modifying it. */
public final class LootCompendiumRates {
    private LootCompendiumRates(){}
    public static final class Roll {
        public final LootItem item;public final double probability;public final String note;
        Roll(LootItem item,double probability,String note){this.item=item;this.probability=probability;this.note=note;}
    }
    public static List<Roll> read(LootTable table){
        List<Roll> out=new ArrayList<>();if(table==null)return out;
        if(table.guaranteed!=null)for(LootItem item:table.guaranteed)if(valid(item))out.add(new Roll(item,1,"Guaranteed on the first loot roll"));
        if(table.tables==null)return out;
        // Read the exact stored totals used by rollItems; recalculating them would hide
        // a stale/partially configured native table and publish a different probability.
        double total=table.totalWeight,beforeTable=0;boolean zeroTableSelected=false;
        boolean invalid=!Double.isFinite(total)||total<0;
        for(LootTable.ItemsTable sub:table.tables)if(sub!=null&&sub.weight<0)invalid=true;
        for(LootTable.ItemsTable sub:table.tables){
            if(sub==null)continue;
            double chance=invalid?Double.NaN:total==0?(zeroTableSelected?0:1):interval(total,beforeTable,sub.weight);
            zeroTableSelected=true;beforeTable+=sub.weight;
            if(sub.items==null)continue;
            double weight=sub.totalWeight,before=0;boolean zeroItemSelected=false;
            boolean invalidItems=!Double.isFinite(weight)||weight<0;
            for(LootItem item:sub.items)if(item!=null&&item.weight<0)invalidItems=true;
            for(LootItem item:sub.items){
                if(item==null)continue;
                // A zero-weight entry is awarded only if the preceding weighted entries were passed.
                double inTable=invalidItems?Double.NaN:item.weight==0?(weight==0?(zeroItemSelected?0:1):Math.max(0,1-before/weight)):
                        weight==0?(zeroItemSelected?0:1):interval(weight,before,item.weight);
                if(item.weight>0){before+=item.weight;zeroItemSelected=true;}
                double probability=chance*inTable;
                if(valid(item)&&(probability>0||Double.isNaN(probability)))out.add(new Roll(item,Math.min(1,probability),
                        invalid||invalidItems?"Invalid configured weights; base chance unavailable":sub.name==null?"Base weighted loot roll":sub.name));
            }
        }
        return out;
    }
    private static double interval(double total,double before,double weight){return Math.max(0,Math.min(total,before+weight)-Math.max(0,before))/total;}
    private static boolean valid(LootItem item){return item!=null&&item.id>=0&&item.id<65535&&item.max>0&&item.min>=0;}
    public static String display(double probability){
        if(!Double.isFinite(probability)||probability<=0)return "Conditional";
        if(probability>=1)return "Always (100%)";
        double inverse=1/probability;
        return "1 / "+number(inverse)+" ("+number(probability*100)+"%)";
    }
    private static String number(double value){return new java.text.DecimalFormat(value>0&&value<.0001?"0.###E0":value>=100?"#,##0.##":"0.####",java.text.DecimalFormatSymbols.getInstance(Locale.US)).format(value);}
}
