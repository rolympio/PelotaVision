## Referências

1. Tsai, H.-S.; Huang, T. S. (1984). *Multiframe image restoration and registration* —
   https://scholar.google.com/scholar?q=Multiframe+image+restoration+and+registration+Tsai+Huang
2. Schultz, R. R.; Stevenson, R. L. (1992). *Experiments in multiframe image enhancement* —
   https://scholar.google.com/scholar?q=Schultz+Stevenson+super-resolution+image+reconstruction
3. Capel, D. C.; Zisserman, A.; Pountain, C. (2003). *A posteriori probability spaces for multi-frame
   super-resolution* (Oxford VGG) —
   https://www.robots.ox.ac.uk/~vgg/publications/2003/Capel03/capel03.pdf
4. Baker, S.; Kanade, T. (CVPR 2002). *Limits on super-resolution and how to break them* —
   https://publications.ri.cmu.edu/storage/publications/pub_files/pub3/baker_simon_2002_4/baker_simon_2002_4.pdf
5. Irani, M.; Peleg, S. (1991). *Improving resolution by image registration*, CVGIP: Graphical Models and
   Image Processing 53(3) — a raiz da back-projection iterativa —
   https://doi.org/10.1016/1049-9652%2891%2990045-L
6. Guizar-Sicairos, Thurman & Fienup (2008). *Efficient subpixel image registration algorithms*,
   Opt. Lett. 33:156 — https://doi.org/10.1364/OL.33.000156
7. Ben-Ezra, Zomet & Nayar (TPAMI 2005). *Video super-resolution using controlled subpixel detector
   shifts (Jitter Camera)* — https://cave.cs.columbia.edu/Statics/publications/pdfs/BenEzra_PAMI05.pdf
8. Litterio, Hodge, Srinivasan et al. (ICCP 2025). *Non-uniform phase-shift super-resolution* (fator 8 com
   offsets mecânicos) — https://arxiv.org/html/2505.15961v2
9. *Subpixel accuracy analysis of phase correlation shift measurement methods applied to satellite
   imagery* (números de erro de registro por tipo de cena) —
   https://thesai.org/Downloads/Volume3No12/Paper_33-Subpixel_Accuracy_Analysis_of_Phase_Correlation_Shift_Measurement_Methods_Applied_to_Satellite_Imagery.pdf
10. Wronski et al. (2019). *Handheld Multi-frame Super-Resolution* (Google, SIGGRAPH Asia) —
    https://arxiv.org/abs/1905.03277
11. Jamy-L. *Handheld Multi-Frame Super-Resolution* (reimplementação aberta, MIT; exige RAW) —
    https://github.com/Jamy-L/Handheld-Multi-Frame-Super-Resolution
12. Lafenetre, Facciolo & Eboli (2023). *Handheld Multi-frame Super-resolution*, IPOL — artigo + demo +
    ZIP — https://www.ipol.im/pub/art/2023/460/ (DOI: https://doi.org/10.5201/ipol.2023.460)
13. Wronski et al. (2019), mesma referência de [10] — fonte dos números de saturação de nitidez com N e do
    *deghosting*.
14. Kasson, J. *Does pixel shift increase resolution?* (análise de MTF) —
    https://blog.kasson.com/the-last-word/does-pixel-shift-increase-resolution/
15. OpenCV, *Motion Analysis and Object Tracking* (`phaseCorrelate`, `findTransformECC`) —
    https://docs.opencv.org/4.13.0/d7/df3/group__imgproc__motion.html
16. OpenCV, *Optical Flow* (DIS, Farneback) —
    https://docs.opencv.org/4.13.0/dc/d6b/group__video__track.html
17. OpenCV, *dnn_superres* (EDSR/ESPCN/FSRCNN/LapSRN) —
    https://docs.opencv.org/4.13.0/d9/de0/group__dnn__superres.html
18. Fruchter, A.; Hook, R. (2002). *Drizzle: a step for image reconstruction*, PASP 114 — referência
    original do algoritmo. **URL editorial não verificada nesta pesquisa**; ver implementações em
    [19] e [21].
19. STScI, *DrizzlePac* — notebook de otimização de amostragem no drizzle —
    https://spacetelescope.github.io/hst_notebooks/notebooks/DrizzlePac/optimize_image_sampling/optimize_image_sampling.html
20. Azzari, A. et al. (2015). *Kernel regression for multi-frame super-resolution*, IPOL —
    https://www.ipol.im/pub/art/2015/130/ (autoria/ID informados pela busca; **página não carregada
    nesta pesquisa — confirme antes de citar formalmente**)
21. NASA/STScI, `drizzle` (Python, BSD) — https://github.com/spacetelescope/drizzle
22. kunzmi, *ImageStackAlignator* (GPL-3.0, RAW/DNG) — https://github.com/kunzmi/ImageStackAlignator
23. skittleson, *burst-superres* (JPEG/PNG, 2×) — https://github.com/skittleson/burst-superres ;
    medição independente do ganho: https://docodethatmatters.com/burst-superres/
24. scikit-image, `skimage.registration` (`phase_cross_correlation`, `upsample_factor`) —
    https://scikit-image.org/docs/stable/api/skimage.registration.html
25. Sony, *Pixel shift multi shooting* (4/16 RAW, passo de ½–1 px, tripé) —
    https://support.d-imaging.sony.co.jp/app/imagingedge/en/instruction/1_2_about_psm.php
26. OM System, *High-Res Shot* ("não compatível com alvos em movimento") —
    https://explore.omsystem.com/us/en/high-res-shot
27. Ricoh, *Pixel Shift Resolution* (Pentax K-1 II) —
    https://www.ricoh-imaging.co.jp/english/products/k-1-2/feature/02.html ; Nikon Z8, guia de pixel shift —
    https://onlinemanual.nikonimglib.com/technicalguide/z8/pixelshift/en/scene_with_pixelshift_enabled_4.html
28. Gupta, N. et al. *Deep Burst Super-Resolution* (BSRN, RAW Bayer, 14 frames) —
    https://github.com/goutamgmb/deep-burst-sr
29. *NTIRE21 Burst Super-Resolution Challenge* — https://github.com/goutamgmb/NTIRE21_BURSTSR
30. *Burstormer* (burst-SR com transformer não-supervisionado) —
    https://github.com/HongYang222/Burstormer
31. Bhat et al. (CVPR 2023). *Self-Supervised Burst Super-Resolution* —
    https://openaccess.thecvf.com/content/CVPR2023/html/Bhat_Self-Supervised_Burst_Super-Resolution_CVPR_2023_paper.html
32. Li, X. et al. *Real-ESRGAN* (BSD-3) — https://github.com/xinntao/Real-ESRGAN
33. Cai et al. *RLFN* (Apache-2.0; 0,317 M parâmetros, 27,11 ms) — https://github.com/bytedance/RLFN
34. SwinIR — https://github.com/JingyunLiang/SwinIR ; HAT — https://github.com/XPixelGroup/HAT ;
    SReNet (SR auto-supervisionado) — https://github.com/aaugustinus/SReNet
35. Seweryn et al. (2024). *Super-resolution for football player detection in SoccerNet* —
    https://arxiv.org/abs/2402.00163
36. *Rethinking Super-Resolution for Near-Sensor Object Detection* (SR derruba mAP sem retreinar) —
    https://onsensor-vision.github.io/pdf/Rethinking_Super-Resolution_for_Near-Sensor_Object_Detection.pdf
37. PIPP, *Astronomy Image Processor* (seleção de quadros por nitidez) —
    https://sites.google.com/view/sequator/manual
38. AutoStakkert! (empilhamento *lucky imaging*) — https://www.autostakkert.com/
39. Siril, tutorial de *lucky imaging* — https://siril.org/tutorials/lucky/
40._tracknet/ — https://ar5iv.labs.arxiv.org/html/1907.03698 ; código:
    https://github.com/yastrebksv/TrackNet ; Hawk-Eye: https://en.wikipedia.org/wiki/Hawk-Eye
41. Kienzle et al. (WACV 2026). *Uplifting Table Tennis: a robust real-world application for 3D
    trajectory estimation* —
    https://openaccess.thecvf.com/content/WACV2026/papers/Kienzle_Uplifting_Table_Tennis_A_Robust_Real-World_Application_for_3D_Trajectory_WACV_2026_paper.pdf
42. Krutul et al. (2026). *Enhancing object detection accuracy in visually challenging scenarios using
    super-resolution* — https://github.com/dkrutul/Enhancing-Object-Detection-Accuracy-in-Visually-Challenging-Scenarios-Using-Super-Resolution
