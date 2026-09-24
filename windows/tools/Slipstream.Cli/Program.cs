using Slipstream.Cli;
using Slipstream.Core.Protocol;

// Slipstream command line.
//   slipstream hub   headless hub with a console output (runs on macOS and Linux too)
//   slipstream sim   fake phone: INPUT with configurable loss, RTT and loss from STATUS
//   slipstream code  print a fresh pairing code

string command = args.Length > 0 ? args[0] : "help";
string[] rest = args.Skip(1).ToArray();
try
{
    return command switch
    {
        "hub" => HubCommand.Run(rest),
        "sim" => SimCommand.Run(rest),
        "code" => PrintCode(),
        "help" or "--help" or "-h" => Help(0),
        _ => throw new UsageException($"Unknown command '{command}'."),
    };
}
catch (UsageException ex)
{
    Console.Error.WriteLine(ex.Message);
    Console.Error.WriteLine();
    return Help(64);
}

static int PrintCode()
{
    Console.WriteLine(Pairing.Display(Pairing.GenerateCode()));
    return 0;
}

static int Help(int exitCode)
{
    TextWriter w = exitCode == 0 ? Console.Out : Console.Error;
    w.WriteLine("Slipstream command line");
    w.WriteLine();
    w.WriteLine(HubCommand.Usage);
    w.WriteLine();
    w.WriteLine(SimCommand.Usage);
    w.WriteLine();
    w.WriteLine("slipstream code");
    return exitCode;
}
