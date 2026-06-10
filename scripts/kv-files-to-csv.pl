#!/usr/bin/env perl
# SPDX-License-Identifier: Apache-2.0
#
# Merge several "key = value" text files into a single CSV matrix.
#
# Each input file (passed as a command-line argument) holds lines of the form
#   <key> = <value>
# Lines that start with '#' (a comment) or that contain no '=' (no key/value
# pair) are ignored. Surrounding whitespace around the key and value is trimmed.
#
# The resulting CSV has:
#   header row -> Key,<file 1 name>,<file 2 name>,...
#   data rows  -> <key>,<value in file 1>,<value in file 2>,...
#
# The key column is the union (superset) of every key seen across all files,
# listed in alphabetical order. A key absent from a given file leaves that
# file's cell empty.
#
# If a row's values are not all identical across the files (including the
# empty-string value used for a file in which the key is absent), the key
# name in that row is prefixed with '******' to flag the discrepancy.
#
# Usage:
#   scripts/kv-files-to-csv.pl file1.txt file2.txt [...]            > out.csv
#   scripts/kv-files-to-csv.pl -o out.csv file1.txt file2.txt [...]

use strict;
use warnings;
use File::Basename qw(basename);

my $out_file;

# Minimal option parsing: -o/--output <file> selects the destination,
# everything else is treated as an input file.
my @inputs;
while (@ARGV) {
    my $arg = shift @ARGV;
    if ($arg eq '-o' || $arg eq '--output') {
        $out_file = shift @ARGV
            or die "$0: $arg requires a filename argument\n";
    }
    elsif ($arg eq '-h' || $arg eq '--help') {
        print <<"USAGE";
Usage: $0 [-o output.csv] file1 file2 [...]

Merge "key = value" text files into a CSV matrix (one column per file).
Lines beginning with '#' or without an '=' are ignored. Keys are emitted
as the alphabetical union of keys across all files; missing cells are blank.
USAGE
        exit 0;
    }
    else {
        push @inputs, $arg;
    }
}

die "Usage: $0 [-o output.csv] <file1> <file2> [...]\n" unless @inputs;

# Column headers, in the order files were given on the command line.
my @headers = map { basename($_) } @inputs;

# values{key}[col] = value of $key in the col-th input file.
my %values;
my %seen_key;

for my $col (0 .. $#inputs) {
    my $path = $inputs[$col];
    open my $fh, '<', $path
        or die "$0: cannot open '$path': $!\n";

    while (my $line = <$fh>) {
        $line =~ s/\r?\n\z//;        # strip trailing newline (LF or CRLF)
        next if $line =~ /^\s*#/;    # comment line
        next unless $line =~ /=/;    # not a key/value pair

        my ($key, $value) = split /=/, $line, 2;
        $key   =~ s/^\s+|\s+$//g;
        $value =~ s/^\s+|\s+$//g;
        next if $key eq '';          # nothing before '='

        $seen_key{$key}    = 1;
        $values{$key}[$col] = $value;
    }

    close $fh;
}

# Quote a single CSV field only when it contains a comma, double-quote, or
# newline (RFC 4180 style: wrap in quotes and double any embedded quote).
sub csv_field {
    my ($field) = @_;
    $field = '' unless defined $field;
    if ($field =~ /["\n\r,]/) {
        $field =~ s/"/""/g;
        return qq{"$field"};
    }
    return $field;
}

sub csv_row {
    return join(',', map { csv_field($_) } @_) . "\n";
}

# Choose the output handle: a named file, or STDOUT when no -o was given.
my $out;
if (defined $out_file) {
    open $out, '>', $out_file
        or die "$0: cannot write '$out_file': $!\n";
}
else {
    $out = \*STDOUT;
}

print {$out} csv_row('Key', @headers);

for my $key (sort keys %seen_key) {
    # Normalize missing cells to '' so a key present in only some files counts
    # as a discrepancy against the empty cells of the files that lack it.
    my @cells = map { defined $values{$key}[$_] ? $values{$key}[$_] : '' }
        0 .. $#inputs;

    # Flag the row when its values are not all identical.
    my $all_same = !grep { $_ ne $cells[0] } @cells;
    my $label = $all_same ? $key : "******$key";

    print {$out} csv_row($label, @cells);
}

close $out if defined $out_file;
