import qbs
import "moc.js" as Moc

QtHostTool {
    type: base.concat("qt.moc-tool")
    useBootstrapLib: true

    Depends { name: "QtCoreHeaders" }

    cpp.defines: base.concat(["QT_NO_COMPRESS"])

    Group {
        name: "source"
        files: [
            "token.cpp",
            "generator.cpp",
            "main.cpp",
            "moc.cpp",
            "parser.cpp",
            "preprocessor.cpp",
        ]
    }

    Properties {
        condition: qbs.targetOS.contains("windows")
        cpp.dynamicLibraries: [
            "shell32",
            "ole32",
        ]
    }

    Group {
        fileTagsFilter: ["application"]
        fileTags: ["qt.moc-tool"]
    }

    Export {
        property stringList extraArguments: []
        Depends { name: "cpp" }
        cpp.includePaths: [importingProduct.buildDirectory + "/qt.headers"]
        Rule {
            name: "QtCoreMocRule"
            inputs: ["objcpp", "cpp", "hpp"]
            explicitlyDependsOn: ["qt.moc-tool"]
            auxiliaryInputs: ["qt_plugin_metadata"]
            excludedAuxiliaryInputs: ["unmocable"]
            outputFileTags: ["hpp", "cpp", "moc_cpp", "unmocable"]
            outputArtifacts: {
                if (input.fileTags.contains("unmocable"))
                    return [];
                var mocinfo = QtMocScanner.apply(input);
                if (!mocinfo.hasQObjectMacro)
                    return [];
                var artifact = { fileTags: ["unmocable"] };
                if (input.fileTags.contains("hpp")) {
                    artifact.filePath = product.buildDirectory + "/qt.headers" + "/moc_"
                        + input.completeBaseName + ".cpp";
                } else {
                    artifact.filePath = product.buildDirectory + "/qt.headers" + '/'
                        + input.completeBaseName + ".moc";
                }
                artifact.fileTags.push(mocinfo.mustCompile ? "cpp" : "hpp");
                if (mocinfo.hasPluginMetaDataMacro)
                    artifact.explicitlyDependsOn = ["qt_plugin_metadata"];
                return [artifact];
            }
            prepare: {
                var cmd = new Command(Moc.fullPath(project),
                                      Moc.args(product, input, output.filePath));
                cmd.description = 'moc ' + input.fileName;
                cmd.highlight = 'codegen';
                return cmd;
            }
        }
    }
}