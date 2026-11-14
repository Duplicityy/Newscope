package io.ruin.content.content242;

import com.google.gson.*;
import io.ruin.api.utils.JsonUtils;
import io.ruin.model.item.ItemContainerG;
import io.ruin.model.item.containers.bank.BankItem;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Only public grant data is read; proves native bank/item attribute serialization. */
public final class OwnerGrantBank242Regression {
    /** Native exposed container field and native BankItem type, without unrelated UI initializers. */
    private static final class DetachedBank extends ItemContainerG<BankItem> {
        protected BankItem newItem(int id,int amount,Map<String,String> attributes){return new BankItem(id,amount,0,attributes);}
        protected BankItem[] newArray(int size){return new BankItem[size];}
    }
    public static void main(String[] args)throws Exception{
        JsonArray rows=new JsonParser().parse(new String(Files.readAllBytes(Paths.get(args[0])),StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("items");
        DetachedBank bank=new DetachedBank();bank.init(800,true);int count=0,charged=0;
        for(JsonElement element:rows){JsonObject row=element.getAsJsonObject();Map<String,String> attributes=new HashMap<>();
            for(Map.Entry<String,JsonElement> a:row.getAsJsonObject("attributes").entrySet())attributes.put(a.getKey(),a.getValue().getAsString());
            bank.set(count++,new BankItem(row.get("id").getAsInt(),row.get("amount").getAsInt(),0,attributes));
        }
        String encoded=JsonUtils.GSON_EXPOSE.toJson(bank);
        DetachedBank reload=JsonUtils.GSON_EXPOSE.fromJson(encoded,DetachedBank.class);reload.init(800,true);
        if(reload.getCount()!=count||reload.getFreeSlots()!=800-count)throw new AssertionError("Native bank capacity/count");
        for(int i=0;i<count;i++){
            BankItem left=bank.get(i),right=reload.get(i),copy=right.copy();
            if(left.getId()!=right.getId()||left.getAmount()!=right.getAmount()||!left.copyOfAttributes().equals(right.copyOfAttributes())||!copy.copyOfAttributes().equals(right.copyOfAttributes()))throw new AssertionError("Native item roundtrip");
            if(right.hasAttributes()){
                if(right.getAmount()!=1||ModernCharges.get(right,0)!=20000)throw new AssertionError("Native charged item state");charged++;
            }
        }
        if(charged!=5)throw new AssertionError("Expected five charged items");
        System.out.println("PASS native BankItem/container serialization: "+count+" grant rows, "+charged+" charged items,800-slot capacity; no real account read or written, no bank UI exercised.");
    }
}
