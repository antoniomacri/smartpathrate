texmain=jump-stats
files=(*.tex)
for jobname in "${files[@]%.*}"
do
   if [ "$jobname" != "$texmain" ] && [ -f "$jobname.tex" ]; then
     echo Compiling: $jobname.tex
     lualatex -jobname=$jobname -job-name=$jobname "\def\filename{$jobname}\input{$texmain.tex}"
     rm $jobname.aux $jobname.log
     echo
   fi
done
