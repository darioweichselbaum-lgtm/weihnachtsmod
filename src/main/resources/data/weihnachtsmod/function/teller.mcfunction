# Teller mit unsichtbarem Rahmen auf dem Boden vor dir.
# Teller: flache weisse Platte (Teppich) als item_display
execute align xyz positioned ~0.5 ~ ~0.5 run summon item_display ~ ~0.41 ~ {Tags:["teller"],item:{id:"minecraft:white_carpet",count:1},item_display:"fixed",transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],scale:[1.6f,1.6f,1.6f]}}
# Unsichtbarer Item Frame, Richtung oben (Facing:1b), dort legst du das Essen hinein
execute align xyz run summon item_frame ~ ~ ~ {Tags:["teller"],Facing:1b,Invisible:1b}
