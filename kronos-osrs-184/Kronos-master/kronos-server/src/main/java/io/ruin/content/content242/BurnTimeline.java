package io.ruin.content.content242;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

/** World-thread, bounded, independent-duration stacks on a shared four-tick target clock. */
public final class BurnTimeline<K,V> {
    public static final int INTERVAL=4, MAX_STACKS=5, MAX_TARGETS=4096;
    private static final class Stack<V>{final V value;final long expires;Stack(V v,long e){value=v;expires=e;}}
    private static final class State<V>{final List<Stack<V>> stacks=new ArrayList<>(MAX_STACKS);long next;State(long tick){next=tick+INTERVAL;}}
    private final IdentityHashMap<K,State<V>> active=new IdentityHashMap<>();
    private long lastPulse=Long.MIN_VALUE;
    private final BiPredicate<K,V> valid;
    private final BiConsumer<K,List<V>> damage;
    public BurnTimeline(BiPredicate<K,V> valid,BiConsumer<K,List<V>> damage){this.valid=Objects.requireNonNull(valid);this.damage=Objects.requireNonNull(damage);}
    public boolean add(K target,V value,long tick,int damage){
        if(target==null||value==null||damage<1||damage>10||tick<0||tick>Long.MAX_VALUE-40||!valid.test(target,value))return false;
        State<V> state=active.get(target);
        if(state!=null){prune(target,state,tick);if(state.stacks.isEmpty()){active.remove(target);state=null;}}
        if(state==null){if(active.size()>=MAX_TARGETS)return false;state=new State<>(tick);active.put(target,state);}
        if(state.stacks.size()>=MAX_STACKS)return false;
        state.stacks.add(new Stack<>(value,tick+(long)damage*INTERVAL));return true;
    }
    private void prune(K target,State<V> state,long tick){state.stacks.removeIf(s->s.expires<tick||!valid.test(target,s.value));}
    public void pulse(long tick){
        if(tick==lastPulse)return;
        if(tick<0||tick<lastPulse){active.clear();lastPulse=tick;return;}
        lastPulse=tick;
        // Dispatch after traversal; a lethal hit may clear another target synchronously.
        List<Map.Entry<K,List<V>>> due=new ArrayList<>();
        Iterator<Map.Entry<K,State<V>>> iterator=active.entrySet().iterator();
        while(iterator.hasNext()){
            Map.Entry<K,State<V>> entry=iterator.next();K target=entry.getKey();State<V> state=entry.getValue();prune(target,state,tick);
            if(!state.stacks.isEmpty()&&tick>=state.next){
                List<V> values=new ArrayList<>(MAX_STACKS);for(Stack<V> stack:state.stacks)values.add(stack.value);
                due.add(new AbstractMap.SimpleImmutableEntry<>(target,Collections.unmodifiableList(values)));
                state.next=tick+INTERVAL;state.stacks.removeIf(s->s.expires<=tick);
            }
            if(state.stacks.isEmpty())iterator.remove();
        }
        for(Map.Entry<K,List<V>> entry:due)damage.accept(entry.getKey(),entry.getValue());
    }
    public void clear(K target){active.remove(target);}
    public int targets(){return active.size();}
    public int remaining(K target){
        State<V> state=active.get(target);if(state==null)return 0;int total=0;
        for(Stack<V> stack:state.stacks)total+=(int)Math.max(0,(stack.expires-state.next)/INTERVAL+1);return total;
    }
}
