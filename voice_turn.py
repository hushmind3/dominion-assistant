"""Interruption invalidates speech output without claiming cancellation of host work."""
class VoiceTurnGate:
    def __init__(self):
        self.epoch=0
        self.latest=None
    def interrupt(self):
        self.epoch+=1
        self.latest=None
    def offer(self,text):
        self.latest=(self.epoch,text)
    def take(self):
        item=self.latest
        self.latest=None
        return item
    def current(self,epoch):
        return self.epoch==epoch
