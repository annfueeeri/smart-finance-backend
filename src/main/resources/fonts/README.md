# PDF中文字体

NotoSansSC-Regular.ttf来自Noto CJK项目，使用SIL Open Font License 1.1（同目录OFL.txt）。
源文件：https://github.com/notofonts/noto-cjk/blob/main/Sans/Variable/TTF/Subset/NotoSansSC-VF.ttf
许可来源：https://github.com/notofonts/noto-cjk/blob/main/Sans/LICENSE

使用fontTools 4.61.1 `fontTools.varLib.instancer.instantiateVariableFont(font, {"wght": 400})`
生成常规字重静态TrueType文件，供PDFBox 3嵌入子集。字体随应用打包，运行时不需Python/fontTools或系统字体。
