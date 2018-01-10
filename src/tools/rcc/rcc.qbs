import qbs

QtHostTool {
    type: base.concat("qt.rcc-tool")
    useBootstrapLib: true
    cpp.includePaths: [path].concat(base)

    Group {
        name: "source"
        files: [
            "main.cpp",
            "rcc.cpp",
            "rcc.h",
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
        fileTags: ["qt.rcc-tool"]
    }

    Export {
        Rule {
            inputs: ["rcc"]
            explicitlyDependsOn: ["qt.rcc-tool"]
            Artifact {
                fileTags: "cpp"
                filePath: product.buildDirectory + "/.rcc/" + input.baseName + "_rcc.cpp"
            }
            prepare: {
                var cmd = new Command(project.binDirectory  + "/rcc", [
                    input.filePath,
                    "--name", input.baseName,
                    "-o", output.filePath,
                ]);
                cmd.description = "rcc " + input.fileName;
                cmd.highlight = "codegen";
                return cmd;
            }
        }

        FileTagger {
            patterns: "*.qrc"
            fileTags: "rcc"
        }
    }
}