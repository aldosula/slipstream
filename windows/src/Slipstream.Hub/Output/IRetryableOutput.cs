namespace Slipstream.Hub.Output;

/// <summary>An output that can check its driver again after the user fixed something.</summary>
public interface IRetryableOutput
{
    void Retry();
}
