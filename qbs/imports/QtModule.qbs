import qbs
import qbs.FileInfo
import qbs.Process
import qbs.TextFile

QtProduct {
    type: [QtGlobalConfig.staticBuild ? "staticlibrary" : "dynamiclibrary"]
    version: project.version
    //destinationDirectory: project.libDirectory
    condition: project.conditionFunction()

    property string simpleName
    property string upperCaseSimpleName: simpleName.toUpperCase()
    targetName: "Qt" + (!bundle.isBundle ? "5" : "") + project.name.slice(2)

    Depends { name: "qt_sse2"; condition: !hostBuild }

    property bool hasUiKit: qbs.targetOS.containsAny(["ios", "tvos", "watchos"])

// TODO: For Windows: qt_targets.prf

// TODO: Create pkgconfig files; see qt_module.prf and qmake
// TODO: Create libtool files; see qt_module.prf and qmake source code

    cpp.useCxxPrecompiledHeader: QtGlobalPrivateConfig.precompile_header
    Properties {
        condition: qbs.targetOS.contains("darwin") && !qbs.toolchain.contains("clang")
        cpp.commonCompilerFlags: ["-fconstant-cfstrings"]
    }
    Properties {
        condition: qbs.targetOS.contains("aix")
        cpp.commonCompilerFlags: ["-mminimal-toc"]
    }
    Properties {
        condition: qbs.targetOS.contains("darwin") && QtGlobalConfig.rpath
        cpp.sonamePrefix: "@rpath"
    }
    Properties {
        condition: qbs.targetOS.contains("darwin") && !QtGlobalConfig.rpath
        // TODO:       CONFIG += absolute_library_soname
    }

    Depends { name: "bundle" }
    bundle.isBundle: QtGlobalConfig.qt_framework // TODO: Further framework stuff in qt_module.prf

// TODO: Do we want/need something along the lines of relative_qt_rpath?
// TODO: Concept of an "internal module" (platform support, platform plugin, bootstrap*, qtzlib)

// darwin:!no_app_extension_api_only: CONFIG += app_extension_api_only

/*
integrity:CONFIG(exceptions, exceptions|exceptions_off): \
    MODULE_CONFIG += exceptions
*/

/* TODO. Relevant for plugins as well (qt_common.prf)
    if(!host_build|!cross_compile):qtConfig(reduce_exports): CONFIG += hide_symbols
    unix:qtConfig(reduce_relocations): CONFIG += bsymbolic_functions
    qtConfig(separate_debug_info): CONFIG += separate_debug_info
*/

// TODO: Generate .pri file. Logic is in qt_module_pri.prf

// contains(QT_PRODUCT, OpenSource.*):DEFINES *= QT_OPENSOURCE

    cpp.defines: {
        var defines = base.concat([
            "QT_BUILD_" + upperCaseSimpleName + "_LIB",
            "QT_BUILDING_QT",
            "QT_USE_QSTRINGBUILDER",
            "QT_DEPRECATED_WARNINGS",
            "QT_MOC_COMPAT",
            "QT_NO_CAST_TO_ASCII",
            "QT_ASCII_CAST_WARNINGS",
        ]);
        if (qbs.targetOS.contains("windows"))
            defines.push("_CRT_SECURE_NO_WARNINGS", "_USE_MATH_DEFINES", "QT_DISABLE_DEPRECATED_BEFORE=0x040800");
        else
            defines.push("QT_DISABLE_DEPRECATED_BEFORE=0x050000");
        return defines;
    }

/*
clang {
    apple_clang_ver = $${QT_APPLE_CLANG_MAJOR_VERSION}.$${QT_APPLE_CLANG_MINOR_VERSION}
    reg_clang_ver = $${QT_CLANG_MAJOR_VERSION}.$${QT_CLANG_MINOR_VERSION}
    !lessThan(apple_clang_ver, "5.1")|!lessThan(reg_clang_ver, "3.4"): \
        CONFIG += compiler_supports_fpmath
} else: gcc {
    CONFIG += compiler_supports_fpmath
}

equals(QT_ARCH, i386):contains(QT_CPU_FEATURES.$$QT_ARCH, sse2):compiler_supports_fpmath {
    # Turn on SSE-based floating-point math
    QMAKE_CFLAGS += -mfpmath=sse
    QMAKE_CXXFLAGS += -mfpmath=sse
}
*/

    FileTagger {
        patterns: ["*_pch.h"]
        fileTags: ["cpp_pch_src"]
    }

    FileTagger {
        patterns: ["*_p.h"]
        fileTags: ["qt.private_header"]
    }

    Rule {
        // TODO: Property useVersionScript; e.g.  some platform plugins turn this off
        condition: !QtGlobalConfig.staticBuild && qbs.targetOS.contains("linux")
                   && !qbs.targetOS.contains("android")
        inputs: ["qt.private_header"]
        requiresInputs: false
        Artifact {
            filePath: product.name + ".versionscript"
            fileTags: ["versionscript"]
        }
        multiplex: true
        prepare: {
            var cmd = new JavaScriptCommand();
            cmd.description = "Creating " + output.fileName;
            cmd.sourceCode = function() {
                var version = project.version.split('.');
                var replacerScript = project.qtbaseDir
                        + "/mkspecs/features/data/unix/findclasslist.pl";
                var replaceProcess = new Process();
                // TODO: Make the perl path configurable?
                if (!replaceProcess.start("perl", [replacerScript]))
                    throw "Failed to generate linker version script";
                replaceProcess.writeLine("Qt_" + version[0]
                                         + "_PRIVATE_API {\n qt_private_api_tag*;");
                var privHeaderObjects = inputs["qt.private_header"];
                if (privHeaderObjects) {
                    for (var i = 0; i < privHeaderObjects.length; ++i) {
                        var headerPath = privHeaderObjects[i].filePath;
                        replaceProcess.writeLine("    @FILE:" + headerPath + "@");
                    }
                }
                replaceProcess.writeLine("};");
                var current = "Qt_" + version[0];
                replaceProcess.writeLine(current + " { *; };");
                var versionTag = "qt_version_tag"; // TODO: Append "_" + Qt namespace, if there is one
                for (i = 0; i <= version[1]; ++i) {
                    var previous = current;
                    current = "Qt_" + version[0] + "." + i;
                    if (i == version[1]) {
                        replaceProcess.writeLine(current + " { " + versionTag  + "; } "
                                                 + previous + ";");
                    } else {
                        replaceProcess.writeLine(current + " {} " + previous + ";");
                    }
                }
                replaceProcess.closeWriteChannel();
                if (!replaceProcess.waitForFinished()) {
                    replaceProcess.kill();
                    throw "Failed to generate linker version script";
                }
                if (replaceProcess.exitCode() != 0) {
                    var error = "Failed to generate linker version script";
                    var stderrOutput = replaceProcess.readStdErr();
                    if (stderrOutput && stderrOutput.length > 0)
                        error += ": " + stderrOutput;
                    throw error;
                }
                var outFile = new TextFile(output.filePath, TextFile.WriteOnly);
                outFile.write(replaceProcess.readStdOut());
                outFile.close();
                replaceProcess.close();
            };
            return [cmd];
        }
    }

// TODO: Installation stuff probably needs some tweaking, see qt_installs.prf
    Group {
        fileTagsFilter: [
            "dynamiclibrary_import",
            "dynamiclibrary_symlink",
            "staticlibrary",
        ]
        qbs.install: !bundle.isBundle
        qbs.installDir: "lib"
        qbs.installSourceBase: product.buildDirectory
    }

    Group {
        fileTagsFilter: ["dynamiclibrary"]
        qbs.install: !bundle.isBundle
        qbs.installDir: qbs.targetOS.contains("windows") ? "bin" : "lib"
        qbs.installSourceBase: product.buildDirectory
    }

    Group {
        fileTagsFilter: ["debuginfo_dll"]
        qbs.install: true
        qbs.installDir: qbs.targetOS.contains("windows") && !QtGlobalConfig.staticBuild ? "bin" : "lib"
        qbs.installSourceBase: product.buildDirectory
    }

    Group {
        fileTagsFilter: ["bundle.content"]
        qbs.install: bundle.isBundle
        qbs.installDir: "lib"
        qbs.installSourceBase: product.buildDirectory
    }

    Export {
        Depends { name: "cpp" }
        cpp.defines: base.concat("QT_" + product.upperCaseSimpleName + "_LIB")
    }
}