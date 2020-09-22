// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Checks and fixes format on files with changes.
//
// Run with --help for usage.

// TODO(gspencergoog): Support clang formatting on Windows.
// TODO(gspencergoog): Support Java formatting on Windows.
// TODO(gspencergoog): Convert to null safety.

import 'dart:io';

import 'package:args/args.dart';
import 'package:isolate/isolate.dart';
import 'package:meta/meta.dart';
import 'package:path/path.dart' as path;
import 'package:process_runner/process_runner.dart';
import 'package:process/process.dart';

class FormattingException implements Exception {
  FormattingException(this.message, [this.result]);

  final String message;
  final ProcessResult /*?*/ result;

  int get exitCode => result?.exitCode ?? -1;

  @override
  String toString() {
    final StringBuffer output = StringBuffer(runtimeType.toString());
    output.write(': $message');
    final String stderr = result?.stderr as String ?? '';
    if (stderr.isNotEmpty) {
      output.write(':\n$stderr');
    }
    return output.toString();
  }
}

enum MessageType {
  message,
  error,
  warning,
}

enum FormatCheck {
  clang,
  java,
  whitespace,
  gn,
}

FormatCheck nameToFormatCheck(String name) {
  switch (name) {
    case 'clang':
      return FormatCheck.clang;
    case 'java':
      return FormatCheck.java;
    case 'whitespace':
      return FormatCheck.whitespace;
    case 'gn':
      return FormatCheck.gn;
  }
  assert(false, 'Unknown FormatCheck type $name');
  return null;
}

String formatCheckToName(FormatCheck check) {
  switch (check) {
    case FormatCheck.clang:
      return 'C++/ObjC';
    case FormatCheck.java:
      return 'Java';
    case FormatCheck.whitespace:
      return 'Trailing whitespace';
    case FormatCheck.gn:
      return 'GN';
  }
  assert(false, 'Unhandled FormatCheck type $check');
  return null;
}

List<String> formatCheckNames() {
  List<FormatCheck> allowed;
  if (!Platform.isWindows) {
    allowed = FormatCheck.values;
  } else {
    allowed = <FormatCheck>[FormatCheck.gn, FormatCheck.whitespace];
  }
  return allowed
      .map<String>((FormatCheck check) => check.toString().replaceFirst('$FormatCheck.', ''))
      .toList();
}

Future<String> _runGit(
  List<String> args,
  ProcessRunner processRunner, {
  bool failOk = false,
}) async {
  final ProcessRunnerResult result = await processRunner.runProcess(
    <String>['git', ...args],
    failOk: failOk,
  );
  return result.stdout;
}

typedef MessageCallback = Function(String message, {MessageType type});

/// Base class for format checkers.
///
/// Provides services that all format checkers need.
abstract class FormatChecker {
  FormatChecker({
    ProcessManager /*?*/ processManager,
    @required this.baseGitRef,
    @required this.repoDir,
    @required this.srcDir,
    this.allFiles = false,
    this.messageCallback,
  }) : _processRunner = ProcessRunner(
          defaultWorkingDirectory: repoDir,
          processManager: processManager ?? const LocalProcessManager(),
        );

  /// Factory method that creates subclass format checkers based on the type of check.
  factory FormatChecker.ofType(
    FormatCheck check, {
    ProcessManager /*?*/ processManager,
    @required String baseGitRef,
    @required Directory repoDir,
    @required Directory srcDir,
    bool allFiles = false,
    MessageCallback messageCallback,
  }) {
    switch (check) {
      case FormatCheck.clang:
        return ClangFormatChecker(
          processManager: processManager,
          baseGitRef: baseGitRef,
          repoDir: repoDir,
          srcDir: srcDir,
          allFiles: allFiles,
          messageCallback: messageCallback,
        );
        break;
      case FormatCheck.java:
        return JavaFormatChecker(
          processManager: processManager,
          baseGitRef: baseGitRef,
          repoDir: repoDir,
          srcDir: srcDir,
          allFiles: allFiles,
          messageCallback: messageCallback,
        );
        break;
      case FormatCheck.whitespace:
        return WhitespaceFormatChecker(
          processManager: processManager,
          baseGitRef: baseGitRef,
          repoDir: repoDir,
          srcDir: srcDir,
          allFiles: allFiles,
          messageCallback: messageCallback,
        );
        break;
      case FormatCheck.gn:
        return GnFormatChecker(
          processManager: processManager,
          baseGitRef: baseGitRef,
          repoDir: repoDir,
          srcDir: srcDir,
          allFiles: allFiles,
          messageCallback: messageCallback,
        );
        break;
    }
    assert(false, 'Unhandled FormatCheck type $check');
    return null;
  }

  final ProcessRunner _processRunner;
  final Directory srcDir;
  final Directory repoDir;
  final bool allFiles;
  MessageCallback /*?*/ messageCallback;
  final String baseGitRef;

  /// Override to provide format checking for a specific type.
  Future<bool> checkFormatting();

  /// Override to provide format fixing for a specific type.
  Future<bool> fixFormatting();

  @protected
  void message(String string) => messageCallback?.call(string, type: MessageType.message);

  @protected
  void error(String string) => messageCallback?.call(string, type: MessageType.error);

  @protected
  Future<String> runGit(List<String> args) async => _runGit(args, _processRunner);

  /// Converts a given raw string of code units to a stream that yields those
  /// code units.
  ///
  /// Uses to convert the stdout of a previous command into an input stream for
  /// the next command.
  @protected
  Stream<List<int>> codeUnitsAsStream(List<int> input) async* {
    yield input;
  }

  @protected
  Future<bool> applyPatch(List<String> patches) async {
    final ProcessPool patchPool = ProcessPool(
      processRunner: _processRunner,
      printReport: namedReport('patch'),
    );
    final List<WorkerJob> jobs = patches.map<WorkerJob>((String patch) {
      return WorkerJob(
        <String>['patch', '-p0'],
        stdinRaw: codeUnitsAsStream(patch.codeUnits),
        failOk: true,
      );
    }).toList();
    final List<WorkerJob> completedJobs = await patchPool.runToCompletion(jobs);
    if (patchPool.failedJobs != 0) {
      error('${patchPool.failedJobs} patch${patchPool.failedJobs > 1 ? 'es' : ''} '
          'failed to apply.');
      completedJobs
          .where((WorkerJob job) => job.result.exitCode != 0)
          .map<String>((WorkerJob job) => job.result.output)
          .forEach(message);
    }
    return patchPool.failedJobs == 0;
  }

  /// Gets the list of files to operate on.
  ///
  /// If [allFiles] is true, then returns all git controlled files in the repo
  /// of the given types.
  ///
  /// If [allFiles] is false, then only return those files of the given types
  /// that have changed between the current working tree and the [baseGitRef].
  @protected
  Future<List<String>> getFileList(List<String> types) async {
    String output;
    if (allFiles) {
      output = await runGit(<String>[
        'ls-files',
        '--',
        ...types,
      ]);
    } else {
      output = await runGit(<String>[
        'diff',
        '-U0',
        '--no-color',
        '--diff-filter=d',
        '--name-only',
        baseGitRef,
        '--',
        ...types,
      ]);
    }
    return output.split('\n').where((String line) => line.isNotEmpty).toList();
  }

  /// Generates a reporting function to supply to ProcessRunner to use instead
  /// of the default reporting function.
  @protected
  ProcessPoolProgressReporter namedReport(String name) {
    return (int total, int completed, int inProgress, int pending, int failed) {
      final String percent =
          total == 0 ? '100' : ((100 * completed) ~/ total).toString().padLeft(3);
      final String completedStr = completed.toString().padLeft(3);
      final String totalStr = total.toString().padRight(3);
      final String inProgressStr = inProgress.toString().padLeft(2);
      final String pendingStr = pending.toString().padLeft(3);
      final String failedStr = failed.toString().padLeft(3);

      stderr.write('$name Jobs: $percent% done, '
          '$completedStr/$totalStr completed, '
          '$inProgressStr in progress, '
          '$pendingStr pending, '
          '$failedStr failed.${' ' * 20}\r');
    };
  }

  /// Clears the last printed report line so garbage isn't left on the terminal.
  @protected
  void reportDone() {
    stderr.write('\r${' ' * 100}\r');
  }
}

/// Checks and formats C++/ObjC files using clang-format.
class ClangFormatChecker extends FormatChecker {
  ClangFormatChecker({
    ProcessManager /*?*/ processManager,
    @required String baseGitRef,
    @required Directory repoDir,
    @required Directory srcDir,
    bool allFiles = false,
    MessageCallback messageCallback,
  }) : super(
          processManager: processManager,
          baseGitRef: baseGitRef,
          repoDir: repoDir,
          srcDir: srcDir,
          allFiles: allFiles,
          messageCallback: messageCallback,
        ) {
    /*late*/ String clangOs;
    if (Platform.isLinux) {
      clangOs = 'linux-x64';
    } else if (Platform.isMacOS) {
      clangOs = 'mac-x64';
    } else {
      throw FormattingException(
          "Unknown operating system: don't know how to run clang-format here.");
    }
    clangFormat = File(
      path.join(
        srcDir.absolute.path,
        'buildtools',
        clangOs,
        'clang',
        'bin',
        'clang-format',
      ),
    );
  }

  /*late*/ File clangFormat;

  @override
  Future<bool> checkFormatting() async {
    final List<String> failures = await _getCFormatFailures();
    failures.map(stdout.writeln);
    return failures.isEmpty;
  }

  @override
  Future<bool> fixFormatting() async {
    message('Fixing C++/ObjC formatting...');
    final List<String> failures = await _getCFormatFailures(fixing: true);
    if (failures.isEmpty) {
      return true;
    }
    return await applyPatch(failures);
  }

  Future<String> _getClangFormatVersion() async {
    final ProcessRunnerResult result =
        await _processRunner.runProcess(<String>[clangFormat.path, '--version']);
    return result.stdout.trim();
  }

  Future<List<String>> _getCFormatFailures({bool fixing = false}) async {
    message('Checking C++/ObjC formatting...');
    const List<String> clangFiletypes = <String>[
      '*.c',
      '*.cc',
      '*.cxx',
      '*.cpp',
      '*.h',
      '*.m',
      '*.mm',
    ];
    final List<String> files = await getFileList(clangFiletypes);
    if (files.isEmpty) {
      message('No C++/ObjC files with changes, skipping C++/ObjC format check.');
      return <String>[];
    }
    if (verbose) {
      message('Using ${await _getClangFormatVersion()}');
    }
    final List<WorkerJob> clangJobs = <WorkerJob>[];
    for (String file in files) {
      if (file.trim().isEmpty) {
        continue;
      }
      clangJobs.add(WorkerJob(<String>[clangFormat.path, '--style=file', file.trim()]));
    }
    final ProcessPool clangPool = ProcessPool(
      processRunner: _processRunner,
      printReport: namedReport('clang-format'),
    );
    final Stream<WorkerJob> completedClangFormats = clangPool.startWorkers(clangJobs);
    final List<WorkerJob> diffJobs = <WorkerJob>[];
    await for (final WorkerJob completedJob in completedClangFormats) {
      if (completedJob.result != null && completedJob.result.exitCode == 0) {
        diffJobs.add(
          WorkerJob(<String>['diff', '-u', completedJob.command.last, '-'],
              stdinRaw: codeUnitsAsStream(completedJob.result.stdoutRaw), failOk: true),
        );
      }
    }
    final ProcessPool diffPool = ProcessPool(
      processRunner: _processRunner,
      printReport: namedReport('diff'),
    );
    final List<WorkerJob> completedDiffs = await diffPool.runToCompletion(diffJobs);
    final Iterable<WorkerJob> failed = completedDiffs.where((WorkerJob job) {
      return job.result.exitCode != 0;
    });
    reportDone();
    if (failed.isNotEmpty) {
      final bool plural = failed.length > 1;
      if (fixing) {
        message('Fixing ${failed.length} C++/ObjC file${plural ? 's' : ''}'
            ' which ${plural ? 'were' : 'was'} formatted incorrectly.');
      } else {
        error('Found ${failed.length} C++/ObjC file${plural ? 's' : ''}'
            ' which ${plural ? 'were' : 'was'} formatted incorrectly.');
        for (final WorkerJob job in failed) {
          stdout.write(job.result.stdout);
        }
      }
    } else {
      message('Completed checking ${diffJobs.length} C++/ObjC files with no formatting problems.');
    }
    return failed.map<String>((WorkerJob job) {
      return job.result.stdout;
    }).toList();
  }
}

/// Checks the format of Java files uing the Google Java format checker.
class JavaFormatChecker extends FormatChecker {
  JavaFormatChecker({
    ProcessManager /*?*/ processManager,
    @required String baseGitRef,
    @required Directory repoDir,
    @required Directory srcDir,
    bool allFiles = false,
    MessageCallback messageCallback,
  }) : super(
          processManager: processManager,
          baseGitRef: baseGitRef,
          repoDir: repoDir,
          srcDir: srcDir,
          allFiles: allFiles,
          messageCallback: messageCallback,
        ) {
    googleJavaFormatJar = File(
      path.absolute(
        path.join(
          srcDir.absolute.path,
          'third_party',
          'android_tools',
          'google-java-format',
          'google-java-format-1.7-all-deps.jar',
        ),
      ),
    );
  }

  /*late*/ File googleJavaFormatJar;

  Future<String> _getGoogleJavaFormatVersion() async {
    final ProcessRunnerResult result = await _processRunner
        .runProcess(<String>['java', '-jar', googleJavaFormatJar.path, '--version']);
    return result.stderr.trim();
  }

  @override
  Future<bool> checkFormatting() async {
    final List<String> failures = await _getJavaFormatFailures();
    failures.map(stdout.writeln);
    return failures.isEmpty;
  }

  @override
  Future<bool> fixFormatting() async {
    message('Fixing Java formatting...');
    final List<String> failures = await _getJavaFormatFailures(fixing: true);
    if (failures.isEmpty) {
      return true;
    }
    return await applyPatch(failures);
  }

  Future<String> _getJavaVersion() async {
    final ProcessRunnerResult result =
        await _processRunner.runProcess(<String>['java', '-version']);
    return result.stderr.trim().split('\n')[0];
  }

  Future<List<String>> _getJavaFormatFailures({bool fixing = false}) async {
    message('Checking Java formatting...');
    final List<WorkerJob> formatJobs = <WorkerJob>[];
    final List<String> files = await getFileList(<String>['*.java']);
    if (files.isEmpty) {
      message('No Java files with changes, skipping Java format check.');
      return <String>[];
    }
    String javaVersion = '<unknown>';
    String javaFormatVersion = '<unknown>';
    try {
      javaVersion = await _getJavaVersion();
    } on ProcessRunnerException {
      error('Cannot run Java, skipping Java file formatting!');
      return const <String>[];
    }
    try {
      javaFormatVersion = await _getGoogleJavaFormatVersion();
    } on ProcessRunnerException {
      error('Cannot find google-java-format, skipping Java format check.');
      return const <String>[];
    }
    if (verbose) {
      message('Using $javaFormatVersion with Java $javaVersion');
    }
    for (String file in files) {
      if (file.trim().isEmpty) {
        continue;
      }
      formatJobs.add(
        WorkerJob(
          <String>['java', '-jar', googleJavaFormatJar.path, file.trim()],
        ),
      );
    }
    final ProcessPool formatPool = ProcessPool(
      processRunner: _processRunner,
      printReport: namedReport('Java format'),
    );
    final Stream<WorkerJob> completedClangFormats = formatPool.startWorkers(formatJobs);
    final List<WorkerJob> diffJobs = <WorkerJob>[];
    await for (final WorkerJob completedJob in completedClangFormats) {
      if (completedJob.result != null && completedJob.result.exitCode == 0) {
        diffJobs.add(
          WorkerJob(
            <String>['diff', '-u', completedJob.command.last, '-'],
            stdinRaw: codeUnitsAsStream(completedJob.result.stdoutRaw),
            failOk: true,
          ),
        );
      }
    }
    final ProcessPool diffPool = ProcessPool(
      processRunner: _processRunner,
      printReport: namedReport('diff'),
    );
    final List<WorkerJob> completedDiffs = await diffPool.runToCompletion(diffJobs);
    final Iterable<WorkerJob> failed = completedDiffs.where((WorkerJob job) {
      return job.result.exitCode != 0;
    });
    reportDone();
    if (failed.isNotEmpty) {
      final bool plural = failed.length > 1;
      if (fixing) {
        error('Fixing ${failed.length} Java file${plural ? 's' : ''}'
            ' which ${plural ? 'were' : 'was'} formatted incorrectly.');
      } else {
        error('Found ${failed.length} Java file${plural ? 's' : ''}'
            ' which ${plural ? 'were' : 'was'} formatted incorrectly.');
        for (final WorkerJob job in failed) {
          stdout.write(job.result.stdout);
        }
      }
    } else {
      message('Completed checking ${diffJobs.length} Java files with no formatting problems.');
    }
    return failed.map<String>((WorkerJob job) {
      return job.result.stdout;
    }).toList();
  }
}

/// Checks the format of any BUILD.gn files using the "gn format" command.
class GnFormatChecker extends FormatChecker {
  GnFormatChecker({
    ProcessManager /*?*/ processManager,
    @required String baseGitRef,
    @required Directory repoDir,
    @required Directory srcDir,
    bool allFiles = false,
    MessageCallback messageCallback,
  }) : super(
          processManager: processManager,
          baseGitRef: baseGitRef,
          repoDir: repoDir,
          srcDir: srcDir,
          allFiles: allFiles,
          messageCallback: messageCallback,
        ) {
    gnBinary = File(
      path.join(
        repoDir.absolute.path,
        'third_party',
        'gn',
        Platform.isWindows ? 'gn.exe' : 'gn',
      ),
    );
  }

  /*late*/ File gnBinary;

  @override
  Future<bool> checkFormatting() async {
    message('Checking GN formatting...');
    return (await _runGnCheck(fixing: false)) == 0;
  }

  @override
  Future<bool> fixFormatting() async {
    message('Fixing GN formatting...');
    await _runGnCheck(fixing: true);
    // The GN script shouldn't fail when fixing errors.
    return true;
  }

  Future<int> _runGnCheck({@required bool fixing}) async {
    final List<String> filesToCheck = await getFileList(<String>['*.gn', '*.gni']);

    final List<String> cmd = <String>[
      gnBinary.path,
      'format',
      if (!fixing) '--dry-run',
    ];
    final List<WorkerJob> jobs = <WorkerJob>[];
    for (final String file in filesToCheck) {
      jobs.add(WorkerJob(<String>[...cmd, file]));
    }
    final ProcessPool gnPool = ProcessPool(
      processRunner: _processRunner,
      printReport: namedReport('gn format'),
    );
    final List<WorkerJob> completedJobs = await gnPool.runToCompletion(jobs);
    reportDone();
    final List<String> incorrect = <String>[];
    for (final WorkerJob job in completedJobs) {
      if (job.result.exitCode == 2) {
        incorrect.add('  ${job.command.last}');
      }
      if (job.result.exitCode == 1) {
        // GN has exit code 1 if it had some problem formatting/checking the
        // file.
        throw FormattingException(
          'Unable to format ${job.command.last}:\n${job.result.output}',
        );
      }
    }
    if (incorrect.isNotEmpty) {
      final bool plural = incorrect.length > 1;
      if (fixing) {
        message('Fixed ${incorrect.length} GN file${plural ? 's' : ''}'
            ' which ${plural ? 'were' : 'was'} formatted incorrectly.');
      } else {
        error('Found ${incorrect.length} GN file${plural ? 's' : ''}'
            ' which ${plural ? 'were' : 'was'} formatted incorrectly:');
        incorrect.forEach(stderr.writeln);
      }
    } else {
      message('All GN files formatted correctly.');
    }
    return incorrect.length;
  }
}

@immutable
class _GrepResult {
  const _GrepResult(this.file, this.hits, this.lineNumbers);
  final File file;
  final List<String> hits;
  final List<int> lineNumbers;
}

/// Checks for trailing whitspace in Dart files.
class WhitespaceFormatChecker extends FormatChecker {
  WhitespaceFormatChecker({
    ProcessManager /*?*/ processManager,
    @required String baseGitRef,
    @required Directory repoDir,
    @required Directory srcDir,
    bool allFiles = false,
    MessageCallback messageCallback,
  }) : super(
          processManager: processManager,
          baseGitRef: baseGitRef,
          repoDir: repoDir,
          srcDir: srcDir,
          allFiles: allFiles,
          messageCallback: messageCallback,
        );

  @override
  Future<bool> checkFormatting() async {
    final List<File> failures = await _getWhitespaceFailures();
    return failures.isEmpty;
  }

  static final RegExp trailingWsRegEx = RegExp(r'[ \t]+$', multiLine: true);

  @override
  Future<bool> fixFormatting() async {
    final List<File> failures = await _getWhitespaceFailures();
    if (failures.isNotEmpty) {
      for (File file in failures) {
        stderr.writeln('Fixing $file');
        String contents = file.readAsStringSync();
        contents = contents.replaceAll(trailingWsRegEx, '');
        file.writeAsStringSync(contents);
      }
    }
    return true;
  }

  static Future<_GrepResult> _hasTrailingWhitespace(File file) async {
    final List<String> hits = <String>[];
    final List<int> lineNumbers = <int>[];
    int lineNumber = 0;
    for (final String line in file.readAsLinesSync()) {
      if (trailingWsRegEx.hasMatch(line)) {
        hits.add(line);
        lineNumbers.add(lineNumber);
      }
      lineNumber++;
    }
    if (hits.isEmpty) {
      return null;
    }
    return _GrepResult(file, hits, lineNumbers);
  }

  Stream<_GrepResult> _whereHasTrailingWhitespace(Iterable<File> files) async* {
    final LoadBalancer pool =
        await LoadBalancer.create(Platform.numberOfProcessors, IsolateRunner.spawn);
    for (final File file in files) {
      yield await pool.run<_GrepResult, File>(_hasTrailingWhitespace, file);
    }
  }

  Future<List<File>> _getWhitespaceFailures() async {
    final List<String> files = await getFileList(<String>[
      '*.c',
      '*.cc',
      '*.cpp',
      '*.cxx',
      '*.dart',
      '*.gn',
      '*.gni',
      '*.gradle',
      '*.h',
      '*.java',
      '*.json',
      '*.m',
      '*.mm',
      '*.py',
      '*.sh',
      '*.yaml',
    ]);
    if (files.isEmpty) {
      message('No files that differ, skipping whitespace check.');
      return <File>[];
    }
    message('Checking for trailing whitespace on ${files.length} source '
        'file${files.length > 1 ? 's' : ''}...');

    final ProcessPoolProgressReporter reporter = namedReport('whitespace');
    final List<_GrepResult> found = <_GrepResult>[];
    final int total = files.length;
    int completed = 0;
    int inProgress = Platform.numberOfProcessors;
    int pending = total;
    int failed = 0;
    await for (final _GrepResult result in _whereHasTrailingWhitespace(
      files.map<File>(
        (String file) => File(
          path.join(repoDir.absolute.path, file),
        ),
      ),
    )) {
      if (result == null) {
        completed++;
      } else {
        failed++;
        found.add(result);
      }
      pending--;
      inProgress = pending < Platform.numberOfProcessors ? pending : Platform.numberOfProcessors;
      reporter(total, completed, inProgress, pending, failed);
    }
    reportDone();
    if (found.isNotEmpty) {
      error('Whitespace check failed. The following files have trailing spaces:');
      for (final _GrepResult result in found) {
        for (int i = 0; i < result.hits.length; ++i) {
          message('  ${result.file.path}:${result.lineNumbers[i]}:${result.hits[i]}');
        }
      }
    } else {
      message('No trailing whitespace found.');
    }
    return found.map<File>((_GrepResult result) => result.file).toList();
  }
}

Future<String> _getDiffBaseRevision(ProcessManager processManager, Directory repoDir) async {
  final ProcessRunner processRunner = ProcessRunner(
    defaultWorkingDirectory: repoDir,
    processManager: processManager ?? const LocalProcessManager(),
  );
  String upstream = 'upstream';
  final String upstreamUrl = await _runGit(
    <String>['remote', 'get-url', upstream],
    processRunner,
    failOk: true,
  );
  if (upstreamUrl.isEmpty) {
    upstream = 'origin';
  }
  await _runGit(<String>['fetch', upstream, 'master'], processRunner);
  String result = '';
  try {
    // This is the preferred command to use, but developer checkouts often do
    // not have a clear fork point, so we fall back to just the regular
    // merge-base in that case.
    result = await _runGit(
      <String>['merge-base', '--fork-point', 'FETCH_HEAD', 'HEAD'],
      processRunner,
    );
  } on ProcessRunnerException {
    result = await _runGit(<String>['merge-base', 'FETCH_HEAD', 'HEAD'], processRunner);
  }
  return result.trim();
}

void _usage(ArgParser parser, {int exitCode = 1}) {
  stderr.writeln('format.dart [--help] [--fix] [--all-files] '
      '[--check <${formatCheckNames().join('|')}>]');
  stderr.writeln(parser.usage);
  exit(exitCode);
}

bool verbose = false;

Future<int> main(List<String> arguments) async {
  final ArgParser parser = ArgParser();
  parser.addFlag('help', help: 'Print help.', abbr: 'h');
  parser.addFlag('fix',
      abbr: 'f',
      help: 'Instead of just checking for formatting errors, fix them in place.',
      defaultsTo: false);
  parser.addFlag('all-files',
      abbr: 'a',
      help: 'Instead of just checking for formatting errors in changed files, '
          'check for them in all files.',
      defaultsTo: false);
  parser.addMultiOption('check',
      abbr: 'c',
      allowed: formatCheckNames(),
      defaultsTo: formatCheckNames(),
      help: 'Specifies which checks will be performed. Defaults to all checks. '
          'May be specified more than once to perform multiple types of checks. '
          'On Windows, only whitespace and gn checks are currently supported.');
  parser.addFlag('verbose', help: 'Print verbose output.', defaultsTo: verbose);

  ArgResults options;
  try {
    options = parser.parse(arguments);
  } on FormatException catch (e) {
    stderr.writeln('ERROR: $e');
    _usage(parser, exitCode: 0);
  }

  verbose = options['verbose'] as bool;

  if (options['help'] as bool) {
    _usage(parser, exitCode: 0);
  }

  final File script = File.fromUri(Platform.script).absolute;
  final Directory repoDir = script.parent.parent.parent;
  final Directory srcDir = repoDir.parent;
  if (verbose) {
    stderr.writeln('Repo: $repoDir');
    stderr.writeln('Src: $srcDir');
  }

  void message(String message, {MessageType type = MessageType.message}) {
    switch (type) {
      case MessageType.message:
        stderr.writeln(message);
        break;
      case MessageType.error:
        stderr.writeln('ERROR: $message');
        break;
      case MessageType.warning:
        stderr.writeln('WARNING: $message');
        break;
    }
  }

  const ProcessManager processManager = LocalProcessManager();
  final String baseGitRef = await _getDiffBaseRevision(processManager, repoDir);

  bool result = true;
  final List<String> checks = options['check'] as List<String>;
  try {
    for (final String checkName in checks) {
      final FormatCheck check = nameToFormatCheck(checkName);
      final String humanCheckName = formatCheckToName(check);
      final FormatChecker checker = FormatChecker.ofType(check,
          processManager: processManager,
          baseGitRef: baseGitRef,
          repoDir: repoDir,
          srcDir: srcDir,
          allFiles: options['all-files'] as bool,
          messageCallback: message);
      bool stepResult;
      if (options['fix'] as bool) {
        message('Fixing any $humanCheckName format problems');
        stepResult = await checker.fixFormatting();
        if (!stepResult) {
          message('Unable to apply $humanCheckName format fixes.');
        }
      } else {
        message('Performing $humanCheckName format check');
        stepResult = await checker.checkFormatting();
        if (!stepResult) {
          message('Found $humanCheckName format problems.');
        }
      }
      result = result && stepResult;
    }
  } on FormattingException catch (e) {
    message('ERROR: $e', type: MessageType.error);
  }

  exit(result ? 0 : 1);
}
