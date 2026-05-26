echo Script to convert the demo mp4 to an animated gif

SET mp4gif="D:\dev\ed-wares\Mp4gif\distrib\mp4gif\mp4gif.exe"
%mp4gif% DemoBearit.mp4 DemoBearit.gif "fps=25,scale=800:-1:flags=lanczos,split[s0][s1];[s0]palettegen=max_colors=32[p];[s1][p]paletteuse=dither=bayer"
