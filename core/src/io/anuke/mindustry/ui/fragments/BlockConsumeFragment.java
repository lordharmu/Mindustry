package io.anuke.mindustry.ui.fragments;

import io.anuke.arc.Core;
import io.anuke.arc.collection.ObjectSet;
import io.anuke.arc.graphics.Color;
import io.anuke.arc.math.geom.Vector2;
import io.anuke.arc.scene.Element;
import io.anuke.arc.scene.Group;
import io.anuke.arc.scene.ui.layout.Table;
import io.anuke.arc.util.Align;
import io.anuke.mindustry.core.GameState.State;
import io.anuke.mindustry.entities.TileEntity;
import io.anuke.mindustry.graphics.Palette;
import io.anuke.mindustry.world.Block;
import io.anuke.mindustry.world.Tile;
import io.anuke.mindustry.world.consumers.Consume;

import static io.anuke.mindustry.Vars.*;

public class BlockConsumeFragment extends Fragment{
    private Table table;
    private Tile lastTile;
    private boolean visible;

    @Override
    public void build(Group parent){
        table = new Table();
        table.visible(() -> !state.is(State.menu) && visible);
        table.setTransform(true);

        parent.addChild(new Element(){{update(() -> {
            if(!Core.scene.hasMouse()){
                Tile tile = world.tileWorld(Core.input.mouseWorld().x, Core.input.mouseWorld().y);
                if(tile == null) return;
                tile = tile.target();

                if(tile != lastTile){
                    if(tile.getTeam() == players[0].getTeam() && tile.block().consumes.hasAny()){
                        show(tile);
                    }else if(visible){
                        hide();
                    }
                    lastTile = tile;
                }
            }
        });}});

        parent.setTransform(true);
        parent.addChild(table);
    }

    public void show(Tile tile){
        ObjectSet<Consume> consumers = new ObjectSet<>();
        TileEntity entity = tile.entity;
        Block block = tile.block();

        table.clearChildren();

        rebuild(block, entity);
        visible = true;

        table.update(() -> {

            if(tile.entity == null || state.is(State.menu)){
                hide();
                return;
            }

            boolean rebuild = false;

            for(Consume c : block.consumes.array()){
                boolean valid = c.isOptional() || c.valid(block, entity);

                if(consumers.contains(c) == valid){
                    if(valid){
                        consumers.remove(c);
                    }else{
                        consumers.add(c);
                    }
                    rebuild = true;
                }
            }

            if(rebuild){
                rebuild(block, entity);
            }

            Vector2 v = Core.input.mouseScreen(tile.drawx() - tile.block().size * tilesize / 2f + 0.25f, tile.drawy() + tile.block().size * tilesize / 2f);
            table.pack();
            table.setPosition(v.x, v.y, Align.topRight);
        });

        table.act(Core.graphics.getDeltaTime());
    }

    public void hide(){
        table.clear();
        table.update(() -> {});
        visible = false;
    }

    private void rebuild(Block block, TileEntity entity){
        table.clearChildren();
        table.left();

        int scale = mobile ? 4 : 3;

        for(Consume c : block.consumes.array()){
            if(!c.isOptional() && !c.valid(block, entity)){
                boolean[] hovered = {false};

                table.table("inventory", c::buildTooltip).visible(() -> hovered[0]).height(scale * 10 + 6).padBottom(-4).right().update(t -> {
                    if(t.getChildren().size == 0) t.remove();
                }).get().act(0);

                Table result = table.table(out -> {
                    out.addImage(c.getIcon()).size(10 * scale).color(Color.DARK_GRAY).padRight(-10 * scale).padBottom(-scale * 2);
                    out.addImage(c.getIcon()).size(10 * scale).color(Palette.accent);
                    out.addImage("icon-missing").size(10 * scale).color(Palette.remove).padLeft(-10 * scale);
                }).size(10 * scale).get();

                result.hovered(() -> hovered[0] = true);
                if(!mobile){
                    result.exited(() -> hovered[0] = false);
                }

                table.row();
            }
        }
    }
}
